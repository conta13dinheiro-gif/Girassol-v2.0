#include "projection.hpp"
#include <cassert>
#include <cmath>
#include <limits>
int main() {
    float input[] = {1, 2, -4, 6}; float output[] = {77, 77, 77, 77};
    assert(project_points(input, sizeof(input), output, sizeof(output), 2));
    assert(output[0] == -1 && std::abs(output[1] - 1.56f) < .00001f && output[2] == -10);
    assert(!project_points(input, sizeof(input), output, 4, 2));
    assert(!project_points(input, sizeof(input), input, sizeof(input), 2));
    input[2] = std::numeric_limits<float>::infinity(); output[0] = 99;
    assert(!project_points(input, sizeof(input), output, sizeof(output), 2));
    assert(output[0] == 99);
    assert(!project_points(input, sizeof(input), output, sizeof(output), 65537));
    assert(project_points(nullptr, 0, nullptr, 0, 0));
}
