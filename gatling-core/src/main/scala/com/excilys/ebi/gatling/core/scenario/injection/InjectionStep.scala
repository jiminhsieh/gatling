/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.excilys.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.excilys.ebi.gatling.core.scenario.injection

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.math.{ pow, sqrt }

trait InjectionStep {
	/**
	 * Iterator of time deltas in between any injected user and the beginning of the simulation
	 */
	def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration]

	/**
	 * Number of users to inject
	 */
	val users: Int
}

/**
 * Ramp a given number of users over a given duration
 */
case class RampInjection(val users: Int, duration: FiniteDuration) extends InjectionStep {
	require(users > 0, "The number of users must be a strictly posivite value")

	override def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration] = {
		val interval = duration / (users - 1).max(1)
		Iterator.iterate(0 milliseconds)(_ + interval).take(users) ++ iterator.map(_ + duration)
	}
}

/**
 * Inject users at constant rate : an other expression of a RampInjection
 */
case class ConstantRateInjection(rate: Double, duration: FiniteDuration) extends InjectionStep {
	val users = (duration.toSeconds * rate).toInt
	val ramp = RampInjection(users, duration)
	override def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration] = ramp.chain(iterator)
}

/**
 * Don't injection any user for a given duration
 */
case class NothingForInjection(duration: FiniteDuration) extends InjectionStep {
	override def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration] = iterator.map(_ + duration)
	override val users = 0
}

/**
 * Injection all the users at once
 */
case class AtOnceInjection(val users: Int) extends InjectionStep {
	require(users > 0, "The number of users must be a strictly posivite value")

	override def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration] = Iterator.continually(0 milliseconds).take(users) ++ iterator
}

/**
 * The injection scheduling follows this equation
 * u = r1*t + (r2-r1)/(2*duration)*t²
 *
 * @r1 : initial injection rate in users/seconds
 * @r2 : final injection rate in users/seconds
 * @duration : injection duration
 */
case class RampRateInjection(r1: Double, r2: Double, duration: FiniteDuration) extends InjectionStep {
	require(r1 > 0 && r2 > 0, "injection rates must be strictly positive values")

	override val users = ((r1 + (r2 - r1) / 2) * duration.toSeconds).toInt

	override def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration] = {
		val a = (r2 - r1) / (2 * duration.toSeconds)
		val b = r1
		val b2 = pow(r1, 2)

		def userScheduling(u: Int) = {
			val c = -u
			val delta = b2 - 4 * a * c

			val t = (-b + sqrt(delta)) / (2 * a)
			new FiniteDuration((t * 1000).toLong, TimeUnit.MILLISECONDS)
		}

		Iterator.range(0, users).map(userScheduling(_)) ++ iterator.map(_ + duration)
	}
}

/**
 * Inject users thru multiple separated ramps until reaching the total amount of users
 */
case class SteppedRampsInjection(val users: Int, usersPerRamp: Int, rampDuration: FiniteDuration, waitDuration: FiniteDuration) extends InjectionStep {
	require(users > 0, "the number of users must be strictly positive")
	require(usersPerRamp > 0, "the number of users per ramp must be strictly positive")

	val nothingFor = NothingForInjection(waitDuration)

	def chainFullRamps(nbRamps: Int, iterator: Iterator[FiniteDuration]) = {
		val ramp = RampInjection(usersPerRamp, rampDuration)
		val tail = (1 until nbRamps).foldRight(iterator)((_, iterator) => nothingFor.chain(ramp.chain(iterator)))
		ramp.chain(tail)
	}

	def chainLastRamp(lastUsers: Int, iterator: Iterator[FiniteDuration]) = {
		if (lastUsers != 0) {
			val lastRampDuration = (rampDuration / (usersPerRamp - 1).max(1)) * (lastUsers - 1).max(1)
			RampInjection(lastUsers, lastRampDuration).chain(iterator)
		} else
			iterator
	}

	override def chain(iterator: Iterator[FiniteDuration]) = {
		val lastUsers = users % usersPerRamp
		val lastIterator = chainLastRamp(lastUsers, iterator)
		val nbFullRamps = users / usersPerRamp
		if (nbFullRamps > 0) chainFullRamps(nbFullRamps, nothingFor.chain(lastIterator)) else lastIterator
	}
}
