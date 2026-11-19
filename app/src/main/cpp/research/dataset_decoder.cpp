#include "swap_arena.hpp"
#include <rapidjson/reader.h>
#include <rapidjson/memorystream.h>
#include <rapidjson/writer.h>
#include <rapidjson/stringbuffer.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <stdexcept>
#include <string_view>
#include <vector>
namespace girassol::research {
namespace {
std::atomic<std::int64_t> cancelled{0};
void check_request(std::int64_t id) { if(id>0 && id<=cancelled.load(std::memory_order_relaxed)) throw std::runtime_error("Decodificação suspensa"); }
constexpr size_t field_budget=32768,batch_budget=1500000;
bool preferred(std::string key) {
    std::transform(key.begin(),key.end(),key.begin(),[](unsigned char c){ return std::tolower(c); });
    constexpr std::string_view names[]={"text","question","answer","context","explanation","instruction","output","content","title","response","correct_answer","solution"};
    return std::find(std::begin(names),std::end(names),key)!=std::end(names);
}
struct Row { size_t index;std::string text; };
struct Collector {
    size_t offset,limit,index=0,bytes=0;std::int64_t request;std::vector<Row> rows;
    bool full() const { return rows.size()>=limit || bytes>=batch_budget; }
    void add(std::string text) {
        check_request(request);
        const auto i=index++;
        if(i<offset || text.size()<16 || text.size()>field_budget || text.find('\0')!=std::string::npos) return;
        const size_t cost=text.size()*6+200;
        if(bytes+cost>batch_budget) { bytes=batch_budget;return; }
        bytes+=cost;rows.push_back({i,std::move(text)});
    }
};
struct Handler : rapidjson::BaseReaderHandler<rapidjson::UTF8<>,Handler> {
    Collector& output;int objects=0,arrays=0;std::string key,text;bool invalid=false,stopped=false;
    explicit Handler(Collector& collector):output(collector) {}
    bool StartObject() { if(++objects+arrays>64) return false;if(objects==1) { text.clear();invalid=false; }return true; }
    bool EndObject(rapidjson::SizeType) { if(objects==1) { if(invalid) text.clear();output.add(std::move(text));if(output.full()) { stopped=true;return false; } }--objects;return true; }
    bool StartArray() { return ++arrays+objects<=64; }
    bool EndArray(rapidjson::SizeType) { --arrays;return true; }
    bool Key(const char* value,rapidjson::SizeType length,bool) { if(objects==1) key.assign(value,length);return true; }
    bool String(const char* value,rapidjson::SizeType length,bool) {
        if(objects==1 && arrays<=1 && preferred(key)) {
            if(text.size()+length+key.size()+3>field_budget) { invalid=true;return true; }
            if(!text.empty()) { text+='\n'; }text+=key;text+=": ";text.append(value,length);
        }
        return true;
    }
};
void preflight(std::string_view data,std::int64_t request) {
    bool quoted=false,escaped=false;size_t run=0;
    size_t position=0;
    for(char c:data) {
        if((position++ & 65535)==0) check_request(request);
        if(quoted) {
            if(++run>field_budget) throw std::runtime_error("Campo JSON excede a janela de estudo; use streaming do Datasets Server");
            if(escaped) escaped=false;else if(c=='\\') escaped=true;else if(c=='"') quoted=false;
        } else if(c=='"') { quoted=true;run=0; }
    }
}
void json_records(std::string_view data,Collector& output,bool lines) {
    preflight(data,output.request);
    if(lines) {
        size_t position=0;
        while(position<data.size() && !output.full()) {
            const auto end=data.find('\n',position);const auto size=(end==std::string_view::npos ? data.size() : end)-position;
            if(size && data.substr(position,size).find_first_not_of(" \r\t")!=std::string_view::npos) {
                rapidjson::MemoryStream stream(data.data()+position,size);rapidjson::Reader reader;Handler handler(output);
                const auto result=reader.Parse<rapidjson::kParseIterativeFlag|rapidjson::kParseValidateEncodingFlag>(stream,handler);
                if(!result && !handler.stopped) throw std::runtime_error("JSONL malformado");
            }
            position+=size+1;
        }
    } else {
        rapidjson::MemoryStream stream(data.data(),data.size());rapidjson::Reader reader;Handler handler(output);
        const auto result=reader.Parse<rapidjson::kParseIterativeFlag|rapidjson::kParseValidateEncodingFlag>(stream,handler);
        if(!result && !handler.stopped) throw std::runtime_error("JSON malformado");
    }
}
void csv_records(std::string_view data,Collector& output) {
    std::vector<std::string> fields,header;std::string field;bool quoted=false,closed=false,invalid=false;size_t record_bytes=0;
    const auto finish_field=[&] { if(fields.size()<256) fields.push_back(std::move(field));else invalid=true;field.clear();closed=false; };
    const auto finish_record=[&] {
        finish_field();
        if(header.empty()) { if(invalid) throw std::runtime_error("Cabeçalho CSV excede a janela de decodificação");header=fields; }
        else {
            std::string text;
            if(!invalid && fields.size()==header.size()) for(size_t i=0;i<header.size();++i) if(preferred(header[i]) && !fields[i].empty()) { if(!text.empty()) text+='\n';text+=header[i]+": "+fields[i]; }
            output.add(std::move(text));
        }
        fields.clear();invalid=false;record_bytes=0;
    };
    for(size_t i=0;i<data.size() && !output.full();++i) {
        if((i & 65535)==0) check_request(output.request);
        const char c=data[i];
        if(++record_bytes>field_budget) invalid=true;
        if(quoted) {
            if(c=='"') { if(i+1<data.size() && data[i+1]=='"') { if(!invalid) field+='"';++i; }else { quoted=false;closed=true; } }
            else if(!invalid) field+=c;
        } else if(c=='"') { if(!field.empty() || closed) throw std::runtime_error("Aspas CSV inválidas");quoted=true; }
        else if(c==',') finish_field();
        else if(c=='\n' || c=='\r') { if(c=='\r' && i+1<data.size() && data[i+1]=='\n') ++i;finish_record(); }
        else { if(closed) throw std::runtime_error("Dados após fechamento CSV");if(!invalid) field+=c; }
    }
    if(quoted) throw std::runtime_error("Registro CSV incompleto");
    if(!output.full() && (!field.empty() || !fields.empty() || closed)) finish_record();
}
}
void cancel_through(std::int64_t request) noexcept {
    auto previous=cancelled.load(std::memory_order_relaxed);
    while(previous<request && !cancelled.compare_exchange_weak(previous,request,std::memory_order_relaxed)) {}
}
std::string extract(const std::string& path,const std::string& format,size_t offset,size_t limit,std::int64_t request) {
    if(limit<1 || limit>100) throw std::runtime_error("Janela de lote inválida");
    NonRootSwapArena arena(path);Collector collector{offset,limit,0,0,request,{}};
    check_request(request);
    const std::string_view data(arena.data(),arena.size());
    if(format=="jsonl") json_records(data,collector,true);
    else if(format=="json") json_records(data,collector,false);
    else if(format=="csv") csv_records(data,collector);
    else throw std::runtime_error("Formato direto não suportado");
    rapidjson::StringBuffer buffer;rapidjson::Writer<rapidjson::StringBuffer,rapidjson::UTF8<>,rapidjson::UTF8<>,rapidjson::CrtAllocator,rapidjson::kWriteValidateEncodingFlag> writer(buffer);
    writer.StartObject();writer.Key("rows");writer.StartArray();
    for(const auto& row:collector.rows) {
        writer.StartObject();writer.Key("row_idx");writer.Uint64(row.index);writer.Key("row");writer.StartObject();writer.Key("text");if(!writer.String(row.text.data(),static_cast<rapidjson::SizeType>(row.text.size()))) throw std::runtime_error("Texto não é UTF-8 válido");writer.EndObject();writer.EndObject();
    }
    writer.EndArray();writer.EndObject();return {buffer.GetString(),buffer.GetSize()};
}
}
