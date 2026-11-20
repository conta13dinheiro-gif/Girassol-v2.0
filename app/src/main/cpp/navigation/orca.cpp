/*
 * Agent.cpp
 * RVO2 Library
 *
 * Copyright 2008 University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Please send all bug reports to <geom@cs.unc.edu>.
 *
 * The authors may be contacted via:
 *
 * Jur van den Berg, Stephen J. Guy, Jamie Snape, Ming C. Lin, Dinesh Manocha
 * Dept. of Computer Science
 * 201 S. Columbia St.
 * Frederick P. Brooks, Jr. Computer Science Bldg.
 * Chapel Hill, N.C. 27599-3175
 * United States of America
 *
 * <http://gamma.cs.unc.edu/RVO2/>
 */

// Girassol adaptation: fixed 150-line storage, bounded inputs, synchronous snapshot velocities.
#include "recast_rvo2_nav.hpp"
#include "../third_party/rvo2/Vector2.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>
namespace girassol::nav {
namespace {
using RVO::Vector2;using RVO::det;using RVO::absSq;using RVO::normalize;using RVO::abs;
constexpr float RVO_EPSILON=0.00001f;
float sqr(float x) { return x*x; }
struct Line { Vector2 point,direction; };
struct Lines {
    std::array<Line,150> data{};std::size_t n=0;
    Lines()=default;
    Lines(const Line* first,const Line* last) { while(first!=last) push_back(*first++); }
    void push_back(Line line) { if(n>=data.size()) throw std::length_error("ORCA line budget");data[n++]=line; }
    const Line* begin() const { return data.data(); }
    std::size_t size() const { return n; }
    const Line& operator[](std::size_t i) const { return data[i]; }
};

	bool linearProgram1(const Lines &lines, size_t lineNo, float radius, const Vector2 &optVelocity, bool directionOpt, Vector2 &result)
	{
		const float dotProduct = lines[lineNo].point * lines[lineNo].direction;
		const float discriminant = sqr(dotProduct) + sqr(radius) - absSq(lines[lineNo].point);

		if (discriminant < 0.0f) {
			/* Max speed circle fully invalidates line lineNo. */
			return false;
		}

		const float sqrtDiscriminant = std::sqrt(discriminant);
		float tLeft = -dotProduct - sqrtDiscriminant;
		float tRight = -dotProduct + sqrtDiscriminant;

		for (size_t i = 0; i < lineNo; ++i) {
			const float denominator = det(lines[lineNo].direction, lines[i].direction);
			const float numerator = det(lines[i].direction, lines[lineNo].point - lines[i].point);

			if (std::fabs(denominator) <= RVO_EPSILON) {
				/* Lines lineNo and i are (almost) parallel. */
				if (numerator < 0.0f) {
					return false;
				}
				else {
					continue;
				}
			}

			const float t = numerator / denominator;

			if (denominator >= 0.0f) {
				/* Line i bounds line lineNo on the right. */
				tRight = std::min(tRight, t);
			}
			else {
				/* Line i bounds line lineNo on the left. */
				tLeft = std::max(tLeft, t);
			}

			if (tLeft > tRight) {
				return false;
			}
		}

		if (directionOpt) {
			/* Optimize direction. */
			if (optVelocity * lines[lineNo].direction > 0.0f) {
				/* Take right extreme. */
				result = lines[lineNo].point + tRight * lines[lineNo].direction;
			}
			else {
				/* Take left extreme. */
				result = lines[lineNo].point + tLeft * lines[lineNo].direction;
			}
		}
		else {
			/* Optimize closest point. */
			const float t = lines[lineNo].direction * (optVelocity - lines[lineNo].point);

			if (t < tLeft) {
				result = lines[lineNo].point + tLeft * lines[lineNo].direction;
			}
			else if (t > tRight) {
				result = lines[lineNo].point + tRight * lines[lineNo].direction;
			}
			else {
				result = lines[lineNo].point + t * lines[lineNo].direction;
			}
		}

		return true;
	}

	size_t linearProgram2(const Lines &lines, float radius, const Vector2 &optVelocity, bool directionOpt, Vector2 &result)
	{
		if (directionOpt) {
			/*
			 * Optimize direction. Note that the optimization velocity is of unit
			 * length in this case.
			 */
			result = optVelocity * radius;
		}
		else if (absSq(optVelocity) > sqr(radius)) {
			/* Optimize closest point and outside circle. */
			result = normalize(optVelocity) * radius;
		}
		else {
			/* Optimize closest point and inside circle. */
			result = optVelocity;
		}

		for (size_t i = 0; i < lines.size(); ++i) {
			if (det(lines[i].direction, lines[i].point - result) > 0.0f) {
				/* Result does not satisfy constraint i. Compute new optimal result. */
				const Vector2 tempResult = result;

				if (!linearProgram1(lines, i, radius, optVelocity, directionOpt, result)) {
					result = tempResult;
					return i;
				}
			}
		}

		return lines.size();
	}

	void linearProgram3(const Lines &lines, size_t numObstLines, size_t beginLine, float radius, Vector2 &result)
	{
		float distance = 0.0f;

		for (size_t i = beginLine; i < lines.size(); ++i) {
			if (det(lines[i].direction, lines[i].point - result) > distance) {
				/* Result does not satisfy constraint of line i. */
				Lines projLines(lines.begin(), lines.begin() + static_cast<ptrdiff_t>(numObstLines));

				for (size_t j = numObstLines; j < i; ++j) {
					Line line;

					float determinant = det(lines[i].direction, lines[j].direction);

					if (std::fabs(determinant) <= RVO_EPSILON) {
						/* Line i and line j are parallel. */
						if (lines[i].direction * lines[j].direction > 0.0f) {
							/* Line i and line j point in the same direction. */
							continue;
						}
						else {
							/* Line i and line j point in opposite direction. */
							line.point = 0.5f * (lines[i].point + lines[j].point);
						}
					}
					else {
						line.point = lines[i].point + (det(lines[j].direction, lines[i].point - lines[j].point) / determinant) * lines[i].direction;
					}

					line.direction = normalize(lines[j].direction - lines[i].direction);
					projLines.push_back(line);
				}

				const Vector2 tempResult = result;

				if (linearProgram2(projLines, radius, Vector2(-lines[i].direction.y(), lines[i].direction.x()), true, result) < projLines.size()) {
					/* This should in principle not happen.  The result is by definition
					 * already in the feasible region of this linear program. If it fails,
					 * it is due to small floating point error, and the current result is
					 * kept.
					 */
					result = tempResult;
				}

				distance = det(lines[i].direction, lines[i].point - result);
			}
		}
	}
}
Vec orca(std::size_t self,const Vec* positions,const Vec* velocities,std::size_t count,Vec preferred,double radius,double speed,double dt) {
    if(!positions || !velocities || count<1 || count>150 || self>=count || !std::isfinite(radius) || radius<=0 || radius>1 ||
       !std::isfinite(speed) || speed<=0 || speed>10 || !std::isfinite(dt) || dt<=0 || dt>1 ||
       !std::isfinite(preferred.x) || !std::isfinite(preferred.z)) throw std::invalid_argument("ORCA input budget");
    for(std::size_t j=0;j<count;++j) if(!std::isfinite(positions[j].x) || !std::isfinite(positions[j].z) || std::abs(positions[j].x)>1000 || std::abs(positions[j].z)>1000 ||
       !std::isfinite(velocities[j].x) || !std::isfinite(velocities[j].z) || std::hypot(velocities[j].x,velocities[j].z)>speed+1e-5) throw std::invalid_argument("ORCA state");
    const Vector2 p(static_cast<float>(positions[self].x),static_cast<float>(positions[self].z));
    const Vector2 v(static_cast<float>(velocities[self].x),static_cast<float>(velocities[self].z));
    Lines lines;
    for(std::size_t j=0;j<count;++j) if(j!=self) {
        const Vector2 relativePosition=Vector2(static_cast<float>(positions[j].x),static_cast<float>(positions[j].z))-p;
        const Vector2 relativeVelocity=v-Vector2(static_cast<float>(velocities[j].x),static_cast<float>(velocities[j].z));
        const float distanceSq=absSq(relativePosition),combined=static_cast<float>(radius*2),combinedSq=combined*combined;
        if(distanceSq>static_cast<float>((4*speed+2*radius)*(4*speed+2*radius))) continue;
        Line line;Vector2 u;
        if(distanceSq>combinedSq) {
            const Vector2 w=relativeVelocity-.5f*relativePosition;const float wSq=absSq(w),dot=w*relativePosition;
            if(dot<0 && dot*dot>combinedSq*wSq) {
                const float length=std::sqrt(wSq);const Vector2 unit=w/length;
                line.direction=Vector2(unit.y(),-unit.x());u=(combined*.5f-length)*unit;
            } else {
                const float leg=std::sqrt(std::max(0.f,distanceSq-combinedSq));
                if(det(relativePosition,w)>0) line.direction=Vector2(relativePosition.x()*leg-relativePosition.y()*combined,relativePosition.x()*combined+relativePosition.y()*leg)/distanceSq;
                else line.direction=-Vector2(relativePosition.x()*leg+relativePosition.y()*combined,-relativePosition.x()*combined+relativePosition.y()*leg)/distanceSq;
                u=(relativeVelocity*line.direction)*line.direction-relativeVelocity;
            }
        } else {
            const Vector2 w=relativeVelocity-relativePosition/static_cast<float>(dt);const float length=abs(w);
            const Vector2 unit=length>1e-8f?w/length:Vector2(self<j?-1.f:1.f,0.f);
            line.direction=Vector2(unit.y(),-unit.x());u=(combined/static_cast<float>(dt)-length)*unit;
        }
        line.point=v+.5f*u;lines.push_back(line);
    }
    Vector2 result;const auto failed=linearProgram2(lines,static_cast<float>(speed),Vector2(static_cast<float>(preferred.x),static_cast<float>(preferred.z)),false,result);
    if(failed<lines.size()) linearProgram3(lines,0,failed,static_cast<float>(speed),result);
    Vec out{result.x(),result.y()};const double length=std::hypot(out.x,out.z);
    if(!std::isfinite(length)) throw std::runtime_error("ORCA nonfinite solution");
    if(length>speed) { out.x*=speed/length;out.z*=speed/length; }
    return out;
}
}
