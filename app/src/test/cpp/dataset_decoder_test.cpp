#include "research/swap_arena.hpp"
#include <cassert>
#include <fstream>
#include <stdexcept>
#include <string>
#include <unistd.h>
template<class F> void rejects(F function) { try { function(); } catch(const std::runtime_error&) { return; }throw std::runtime_error("Expected bounded decoder failure"); }
int main() {
    char name[]="/tmp/girassol-dataset-XXXXXX";const int fd=mkstemp(name);assert(fd>=0);close(fd);
    const auto write=[&](const std::string& text) { std::ofstream file(name);file<<text; };
    write("{\"question\":\"What conserves energy?\",\"answer\":\"An isolated system.\"}\n{\"text\":\"Living systems transform available energy.\"}\n");
    auto rows=girassol::research::extract(name,"jsonl",0);assert(rows.find("isolated system")!=std::string::npos);
    rows=girassol::research::extract(name,"jsonl",1);assert(rows.find("isolated system")==std::string::npos && rows.find("Living systems")!=std::string::npos);
    write("question,answer\r\n\"What is \\\"energy\\\"?\",\"A physical quantity.\"\r\n");
    // CSV escapes quotes by doubling them, never by backslashes.
    rejects([&] { girassol::research::extract(name,"csv",0); });
    write("question,answer\r\n\"What is \"\"energy\"\"?\",\"A physical quantity,\nwhich is conserved.\"\r\n");
    rows=girassol::research::extract(name,"csv",0);assert(rows.find("physical quantity")!=std::string::npos && rows.find("conserved")!=std::string::npos);
    write("[{\"text\":\"A complete record with unicode: ação.\"},{\"text\":\"Another educational text record.\"}]");
    rows=girassol::research::extract(name,"json",0,1);assert(rows.find("Another")==std::string::npos);
    write("{\"text\":\"unterminated");rejects([&] { girassol::research::extract(name,"jsonl",0); });
    write("{\"text\":\""+std::string(40000,'x')+"\"}");rejects([&] { girassol::research::extract(name,"json",0); });
    for(int i=0;i<100;++i) { write("text\nA complete bounded CSV text record.\n");girassol::research::extract(name,"csv",0); }
    girassol::research::cancel_through(5);
    rejects([&] { girassol::research::extract(name,"csv",0,100,5); });
    assert(!girassol::research::extract(name,"csv",0,100,6).empty());
    unlink(name);rejects([&] { girassol::research::extract(name,"csv",0); });
}
