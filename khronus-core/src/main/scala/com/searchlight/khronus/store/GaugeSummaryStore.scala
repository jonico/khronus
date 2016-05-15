/*
 * =========================================================================================
 * Copyright © 2015 the khronus project <https://github.com/hotels-tech/khronus>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package com.searchlight.khronus.store

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

import com.datastax.driver.core.Session
import com.esotericsoftware.kryo.io.{ Input, Output }
import com.searchlight.khronus.model.summary.GaugeSummary
import com.searchlight.khronus.util.Settings

import scala.concurrent.duration._

trait GaugeSummaryStoreSupport extends SummaryStoreSupport[GaugeSummary] {
  override def summaryStore: SummaryStore[GaugeSummary] = Summaries.gaugeSummaryStore
}

class CassandraGaugeSummaryStore(session: Session) extends CassandraSummaryStore[GaugeSummary](session) {

  override def limit = Settings.Gauges.SummaryLimit

  override def fetchSize = Settings.Gauges.SummaryFetchSize

  override def tableName(duration: Duration) = s"gaugeSummary${duration.length}${duration.unit}"

  override def ttl(windowDuration: Duration): Int = Settings.Gauges.SummaryRetentionPolicies(windowDuration).toSeconds.toInt

  override def serializeSummary(summary: GaugeSummary): ByteBuffer = {
    val baos = new ByteArrayOutputStream()
    val output = new Output(baos)
    output.writeByte(1)
    output.writeVarLong(summary.min, true)
    output.writeVarLong(summary.max, true)
    output.writeVarLong(summary.mean, true)
    output.writeVarLong(summary.count, true)
    output.flush()
    baos.flush()
    output.close()
    ByteBuffer.wrap(baos.toByteArray)
  }

  override def deserialize(timestamp: Long, buffer: Array[Byte]): GaugeSummary = {
    val input = new Input(buffer)
    val version: Int = input.readByte
    if (version == 1) {
      val min = input.readVarLong(true)
      val max = input.readVarLong(true)
      val average = input.readVarLong(true)
      val count = input.readVarLong(true)
      input.close()
      GaugeSummary(timestamp, min, max, average, count)
    } else GaugeSummary(timestamp, 0, 0, 0, 0)

  }

}
