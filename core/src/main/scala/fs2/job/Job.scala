/*
 * Copyright 2014–2018 SlamData Inc.
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
 */

package fs2
package job

import scala.Boolean
import scala.util.Either

final case class Job[F[_], I, N, R](id: I, run: Stream[F, Either[N, R]], reportOn: N => Boolean)

object Job {
  def unreported[F[_], I, N, R](id: I, run: Stream[F, Either[N, R]]): Job[F, I, N, R] =
    Job(id, run, (_ => false))

  def reportAll[F[_], I, N, R](id: I, run: Stream[F, Either[N, R]]): Job[F, I, N, R] =
    Job(id, run, (_ => true))
}
