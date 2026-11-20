#include "cognitive/tsetlin_machine.hpp"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <vector>

int main() {
    using girassol::tsetlin::PersistentTsetlinMachine;
    PersistentTsetlinMachine model(8, 32, 64, 3.5f, 8, 0x47525354ULL);
    assert(model.valid());

    const std::vector<std::uint8_t> positive{1,1,1,1,0,0,0,0};
    const std::vector<std::uint8_t> negative{0,0,0,0,1,1,1,1};
    for (int i = 0; i < 400; ++i) {
        model.trainStep(positive, true);
        model.trainStep(negative, false);
    }
    assert(model.valid());
    assert(model.confidence(positive) >= 0.0f && model.confidence(positive) <= 1.0f);

    const auto snapshot = model.serializeBinary();
    assert(snapshot.size() > 32);
    PersistentTsetlinMachine restored(8, 32, 64, 3.5f, 8, 1);
    assert(restored.deserializeBinary(snapshot));
    assert(restored.serializeBinary() == snapshot);
    for (int i = 0; i < 100; ++i) {
        model.trainStep(positive, true);
        restored.trainStep(positive, true);
    }
    assert(model.serializeBinary() == restored.serializeBinary());

    auto corrupt = snapshot;
    corrupt[0] = 'X';
    assert(!restored.deserializeBinary(corrupt));
    corrupt = snapshot;
    corrupt.pop_back();
    assert(!restored.deserializeBinary(corrupt));
    corrupt = snapshot;
    corrupt[32] = 2;
    assert(!restored.deserializeBinary(corrupt));
    assert(!restored.deserializeBinary({}));

    const auto before = model.serializeBinary();
    model.trainStep(std::vector<std::uint8_t>{1,0}, true);
    model.trainStep(std::vector<std::uint8_t>{1,2,0,0,0,0,0,0}, true);
    assert(model.serializeBinary() == before);

    bool rejected = false;
    try { PersistentTsetlinMachine invalid(0, 4); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);

    std::cout << "Tsetlin persistence, deterministic replay, bounds and corruption checks passed\n";
    return 0;
}
