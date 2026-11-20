#include "physics/orca.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

using girassol::physics::orca::Neighbor;
using girassol::physics::orca::Vec2;
using girassol::physics::orca::computeVelocity;

namespace {
double distance(Vec2 a, Vec2 b) {
    return std::hypot(a.x - b.x, a.y - b.y);
}
void bounded(Vec2 velocity, double maximum) {
    assert(std::isfinite(velocity.x));
    assert(std::isfinite(velocity.y));
    assert(std::hypot(velocity.x, velocity.y) <= maximum + 1e-9);
}
}

int main() {
    constexpr double radius = 0.18;
    constexpr double maximumSpeed = 0.9;

    // Symmetric head-on motion must reduce both preferred closing velocities.
    const auto left = computeVelocity(
        {-1.0, 0.0}, {0.5, 0.0}, {0.9, 0.0},
        {{{1.0, 0.0}, {-0.5, 0.0}}}, radius, maximumSpeed
    );
    const auto right = computeVelocity(
        {1.0, 0.0}, {-0.5, 0.0}, {-0.9, 0.0},
        {{{-1.0, 0.0}, {0.5, 0.0}}}, radius, maximumSpeed
    );
    bounded(left, maximumSpeed);
    bounded(right, maximumSpeed);
    assert(left.x < 0.9);
    assert(right.x > -0.9);
    assert(std::abs(left.x + right.x) < 1e-8);

    // Repeated reciprocal updates preserve the minimum separation with a margin.
    Vec2 pLeft{-1.0, 0.0}, pRight{1.0, 0.0};
    Vec2 vLeft{0.5, 0.0}, vRight{-0.5, 0.0};
    for (int tick = 0; tick < 240; ++tick) {
        const auto nextLeft = computeVelocity(
            pLeft, vLeft, {0.9, 0.0}, {{pRight, vRight}}, radius, maximumSpeed
        );
        const auto nextRight = computeVelocity(
            pRight, vRight, {-0.9, 0.0}, {{pLeft, vLeft}}, radius, maximumSpeed
        );
        pLeft.x += nextLeft.x / 60.0;
        pLeft.y += nextLeft.y / 60.0;
        pRight.x += nextRight.x / 60.0;
        pRight.y += nextRight.y / 60.0;
        vLeft = nextLeft;
        vRight = nextRight;
        bounded(vLeft, maximumSpeed);
        bounded(vRight, maximumSpeed);
        assert(distance(pLeft, pRight) >= radius * 2.0 - 1e-5);
    }

    // Stationary neighbor and perpendicular crossing remain finite and deterministic.
    const std::vector<Neighbor> crowd{
        {{0.0, 0.0}, {0.0, 0.0}},
        {{1.0, 1.0}, {-0.2, -0.2}},
        {{-1.0, 0.5}, {0.1, 0.0}}
    };
    const auto first = computeVelocity(
        {-0.5, -0.2}, {0.0, 0.3}, {0.7, 0.2}, crowd, radius, maximumSpeed
    );
    const auto repeated = computeVelocity(
        {-0.5, -0.2}, {0.0, 0.3}, {0.7, 0.2}, crowd, radius, maximumSpeed
    );
    bounded(first, maximumSpeed);
    assert(std::abs(first.x - repeated.x) < 1e-12);
    assert(std::abs(first.y - repeated.y) < 1e-12);

    // Overlap-recovery and invalid-input guards never return NaN or exceed speed.
    const auto overlap = computeVelocity(
        {0.0, 0.0}, {0.0, 0.0}, {0.5, 0.5},
        {{{0.1, 0.0}, {0.0, 0.0}}}, radius, maximumSpeed
    );
    bounded(overlap, maximumSpeed);
    const auto invalid = computeVelocity(
        {0.0, 0.0}, {0.0, 0.0}, {1.0, 0.0}, {}, radius, maximumSpeed, 0.0, 1.0 / 60.0
    );
    assert(invalid.x == 0.0 && invalid.y == 0.0);

    std::cout << "Deterministic reciprocal ORCA solver passed\n";
}
