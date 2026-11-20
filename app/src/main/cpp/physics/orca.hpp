#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

/**
 * Bounded deterministic 2D ORCA velocity selection.
 *
 * Each agent shares half of the pairwise avoidance correction. The selected
 * velocity is the closest feasible point to its preferred velocity inside the
 * max-speed disk. A deterministic sampled boundary and line intersections make
 * the tiny crowd solver allocation-bounded by the supplied neighbor count.
 */
namespace girassol::physics::orca {

struct Vec2 {
    double x = 0.0;
    double y = 0.0;
};

struct Neighbor {
    Vec2 position;
    Vec2 velocity;
};

struct Line {
    Vec2 point;
    Vec2 direction;
};

inline Vec2 add(Vec2 a, Vec2 b) { return {a.x + b.x, a.y + b.y}; }
inline Vec2 sub(Vec2 a, Vec2 b) { return {a.x - b.x, a.y - b.y}; }
inline Vec2 mul(Vec2 a, double s) { return {a.x * s, a.y * s}; }
inline double dot(Vec2 a, Vec2 b) { return a.x * b.x + a.y * b.y; }
inline double det(Vec2 a, Vec2 b) { return a.x * b.y - a.y * b.x; }
inline double normSq(Vec2 a) { return dot(a, a); }
inline double norm(Vec2 a) { return std::sqrt(normSq(a)); }

inline Vec2 clampLength(Vec2 value, double maximum) {
    const double length = norm(value);
    if (length <= maximum || length <= 1e-12) return value;
    return mul(value, maximum / length);
}

inline Vec2 unitOr(Vec2 value, Vec2 fallback) {
    const double length = norm(value);
    return length > 1e-12 ? mul(value, 1.0 / length) : fallback;
}

inline Line makeLine(
    Vec2 position,
    Vec2 velocity,
    const Neighbor& other,
    double combinedRadius,
    double timeHorizon,
    double timeStep
) {
    constexpr double epsilon = 1e-12;
    const Vec2 relativePosition = sub(other.position, position);
    const Vec2 relativeVelocity = sub(velocity, other.velocity);
    const double distanceSquared = normSq(relativePosition);
    const double radiusSquared = combinedRadius * combinedRadius;
    Vec2 correction{};
    Vec2 direction{};

    if (distanceSquared > radiusSquared) {
        const Vec2 w = sub(relativeVelocity, mul(relativePosition, 1.0 / timeHorizon));
        const double wSquared = normSq(w);
        const double projection = dot(w, relativePosition);
        if (projection < 0.0 && projection * projection > radiusSquared * wSquared) {
            const Vec2 unitW = unitOr(w, unitOr(mul(relativePosition, -1.0), {1.0, 0.0}));
            direction = {unitW.y, -unitW.x};
            correction = mul(unitW, combinedRadius / timeHorizon - norm(w));
        } else {
            const double leg = std::sqrt(std::max(0.0, distanceSquared - radiusSquared));
            if (det(relativePosition, w) > 0.0) {
                direction = {
                    (relativePosition.x * leg - relativePosition.y * combinedRadius) / distanceSquared,
                    (relativePosition.x * combinedRadius + relativePosition.y * leg) / distanceSquared
                };
            } else {
                direction = {
                    -(relativePosition.x * leg + relativePosition.y * combinedRadius) / distanceSquared,
                    -(-relativePosition.x * combinedRadius + relativePosition.y * leg) / distanceSquared
                };
            }
            const double projectionOnDirection = dot(relativeVelocity, direction);
            correction = sub(mul(direction, projectionOnDirection), relativeVelocity);
        }
    } else {
        const Vec2 w = sub(relativeVelocity, mul(relativePosition, 1.0 / timeStep));
        const Vec2 unitW = unitOr(w, unitOr(mul(relativePosition, -1.0), {1.0, 0.0}));
        direction = {unitW.y, -unitW.x};
        correction = mul(unitW, combinedRadius / timeStep - norm(w));
    }

    // Pairwise reciprocity: each side accepts half the correction.
    return {add(velocity, mul(correction, 0.5)), unitOr(direction, {0.0, 1.0})};
}

inline double lineViolation(const Line& line, Vec2 candidate) {
    return det(line.direction, sub(line.point, candidate));
}

inline bool feasible(const std::vector<Line>& lines, Vec2 candidate) {
    return std::all_of(lines.begin(), lines.end(), [&](const Line& line) {
        return lineViolation(line, candidate) <= 1e-9;
    });
}

inline Vec2 chooseVelocity(
    const std::vector<Line>& lines,
    Vec2 preferred,
    double maxSpeed
) {
    constexpr int boundarySamples = 128;
    std::vector<Vec2> candidates;
    candidates.reserve(2 + lines.size() + lines.size() * (lines.size() - 1) / 2 + boundarySamples);
    candidates.push_back(clampLength(preferred, maxSpeed));
    candidates.push_back({0.0, 0.0});

    // Closest points on individual ORCA boundaries.
    for (const auto& line : lines) {
        candidates.push_back(add(
            line.point,
            mul(line.direction, dot(sub(preferred, line.point), line.direction))
        ));
    }

    // Intersections include vertices of the feasible half-plane polygon.
    for (std::size_t i = 0; i < lines.size(); ++i) {
        for (std::size_t j = i + 1; j < lines.size(); ++j) {
            const double denominator = det(lines[i].direction, lines[j].direction);
            if (std::abs(denominator) <= 1e-10) continue;
            const double t = det(sub(lines[j].point, lines[i].point), lines[j].direction) / denominator;
            candidates.push_back(add(lines[i].point, mul(lines[i].direction, t)));
        }
    }

    // The speed disk can cut a half-plane edge; sample its boundary deterministically.
    for (int sample = 0; sample < boundarySamples; ++sample) {
        const double angle = (2.0 * 3.14159265358979323846 * sample) / boundarySamples;
        candidates.push_back({maxSpeed * std::cos(angle), maxSpeed * std::sin(angle)});
    }

    Vec2 best = clampLength(preferred, maxSpeed);
    double bestDistance = std::numeric_limits<double>::infinity();
    bool foundFeasible = false;
    double bestViolation = std::numeric_limits<double>::infinity();

    for (const auto candidate : candidates) {
        if (!std::isfinite(candidate.x) || !std::isfinite(candidate.y)) continue;
        if (normSq(candidate) > maxSpeed * maxSpeed + 1e-9) continue;
        double violation = 0.0;
        for (const auto& line : lines) {
            violation += std::max(0.0, lineViolation(line, candidate));
        }
        const double distance = normSq(sub(candidate, preferred));
        const bool candidateFeasible = violation <= 1e-8;
        if (candidateFeasible) {
            if (!foundFeasible || distance < bestDistance - 1e-12) {
                best = candidate;
                bestDistance = distance;
                foundFeasible = true;
            }
        } else if (!foundFeasible &&
                   (violation < bestViolation - 1e-12 ||
                    (std::abs(violation - bestViolation) <= 1e-12 && distance < bestDistance))) {
            best = candidate;
            bestViolation = violation;
            bestDistance = distance;
        }
    }
    return clampLength(best, maxSpeed);
}

inline Vec2 computeVelocity(
    Vec2 position,
    Vec2 currentVelocity,
    Vec2 preferredVelocity,
    const std::vector<Neighbor>& neighbors,
    double agentRadius,
    double maxSpeed,
    double timeHorizon = 2.0,
    double timeStep = 1.0 / 60.0
) {
    if (!std::isfinite(position.x) || !std::isfinite(position.y) ||
        !std::isfinite(currentVelocity.x) || !std::isfinite(currentVelocity.y) ||
        !std::isfinite(preferredVelocity.x) || !std::isfinite(preferredVelocity.y) ||
        !std::isfinite(agentRadius) || !std::isfinite(maxSpeed) ||
        !std::isfinite(timeHorizon) || !std::isfinite(timeStep) ||
        agentRadius <= 0.0 || maxSpeed < 0.0 || timeHorizon <= 0.0 || timeStep <= 0.0) {
        return {0.0, 0.0};
    }
    std::vector<Line> lines;
    lines.reserve(neighbors.size());
    for (const auto& neighbor : neighbors) {
        if (!std::isfinite(neighbor.position.x) || !std::isfinite(neighbor.position.y) ||
            !std::isfinite(neighbor.velocity.x) || !std::isfinite(neighbor.velocity.y)) continue;
        if (normSq(sub(neighbor.position, position)) <= 1e-20) continue;
        lines.push_back(makeLine(
            position, currentVelocity, neighbor, agentRadius * 2.0, timeHorizon, timeStep
        ));
    }
    return chooseVelocity(lines, clampLength(preferredVelocity, maxSpeed), maxSpeed);
}

}  // namespace girassol::physics::orca
