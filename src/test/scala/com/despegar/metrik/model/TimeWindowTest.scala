/*
 * =========================================================================================
 * Copyright © 2014 the metrik project <https://github.com/hotels-tech/metrik>
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

package com.despegar.metrik.model

import com.despegar.metrik.store._
import org.HdrHistogram.Histogram
import org.mockito.{ Matchers, ArgumentMatcher, ArgumentCaptor, Mockito }
import org.scalatest.{ FunSuite }
import org.scalatest.mock.MockitoSugar
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit
import com.despegar.metrik.util.Config

class TimeWindowTest extends FunSuite with MockitoSugar {

  private def getMockedWindow(windowDuration: FiniteDuration, previousWindowDuration: FiniteDuration) = {
    val window = new TimeWindow(windowDuration, previousWindowDuration) with HistogramBucketSupport with StatisticSummarySupport with MetaSupport {
      override val histogramBucketStore = mock[HistogramBucketStore]

      override val statisticSummaryStore = mock[StatisticSummaryStore]

      override val metaStore = mock[MetaStore]
    }
    window
  }

  test("process 30 seconds window should store 2 buckets with their summary statistics") {
    val windowDuration: FiniteDuration = 30 seconds

    //val window = getMockedWindow(windowDuration, 1 millis)
    val window = new TimeWindow(windowDuration, 1 millis) with HistogramBucketSupport with StatisticSummarySupport with MetaSupport {
      override val histogramBucketStore = mock[HistogramBucketStore]

      override val statisticSummaryStore = mock[StatisticSummaryStore]

      override val metaStore = mock[MetaStore]
    }

    //fill mocked histograms.
    val histogram1: Histogram = new Histogram(3000, 3)
    for (i ← 1 to 50) {
      histogram1.recordValue(i)
    }
    val histogram2: Histogram = new Histogram(3000, 3)
    for (i ← 51 to 100) {
      histogram2.recordValue(i)
    }
    val histogram3: Histogram = new Histogram(3000, 3)
    histogram3.recordValue(100L)
    histogram3.recordValue(100L)

    //make 2 buckets
    val bucket1: HistogramBucket = HistogramBucket(1, 1 millis, histogram1)
    val bucket2: HistogramBucket = HistogramBucket(2, 1 millis, histogram2)
    //second bucket
    val bucket3: HistogramBucket = HistogramBucket(30001, 1 millis, histogram3)

    val metricKey: String = "metrickA"
    val executionTime = bucket3.timestamp //The last one

    //mock retrieve slice
    val histograms: Seq[HistogramBucket] = Seq(bucket1, bucket2, bucket3)
    Mockito.when(window.histogramBucketStore.sliceUntil(Matchers.eq(metricKey), Matchers.any[Long], Matchers.eq(1 millis))).thenReturn(Future(histograms))

    val summaryBucketA = StatisticSummary(0, 50, 80, 90, 95, 99, 100, 1, 100, 100, 50.5)
    val summaryBucketB = StatisticSummary(30000, 100, 100, 100, 100, 100, 100, 100, 100, 2, 100)
    Mockito.when(window.statisticSummaryStore.store(metricKey, windowDuration, Seq(summaryBucketB, summaryBucketA))).thenReturn(Future {})
    Mockito.when(window.histogramBucketStore.remove("metrickA", 1 millis, Seq(bucket1, bucket2, bucket3))).thenReturn(Future {})

    //mock summaries
    Mockito.when(window.metaStore.getLastProcessedTimestamp(metricKey)).thenReturn(Future(-Long.MaxValue))

    //call method to test
    val f = window.process(metricKey, executionTime)
    Await.result(f, 5 seconds)

    val histogramBucketA: Histogram = Seq(bucket1, bucket2)
    val histogramBucketB: Histogram = Seq(bucket3)

    //verify the summaries for each bucket
    Mockito.verify(window.statisticSummaryStore).store(metricKey, windowDuration, Seq(summaryBucketB, summaryBucketA))

    //verify removal of previous buckets
    Mockito.verify(window.histogramBucketStore).remove("metrickA", 1 millis, Seq(bucket1, bucket2, bucket3))
  }

  test("RE-process 30 seconds window should not store any statistics") {
    val windowDuration: FiniteDuration = 30 seconds

    //val window = getMockedWindow(windowDuration, 1 millis)
    val window = new TimeWindow(windowDuration, 1 millis) with HistogramBucketSupport with StatisticSummarySupport with MetaSupport {
      override val histogramBucketStore = mock[HistogramBucketStore]

      override val statisticSummaryStore = mock[StatisticSummaryStore]

      override val metaStore = mock[MetaStore]
    }

    //fill mocked histograms.
    val histogram1: Histogram = new Histogram(3000, 3)
    for (i ← 1 to 50) {
      histogram1.recordValue(i)
    }

    //make buckets
    val bucket1: HistogramBucket = HistogramBucket(15000, 1 millis, histogram1)

    val metricKey: String = "metrickA"
    val executionTime = bucket1.timestamp

    //mock temporal data that for any reason was not deleted! (already processed)
    val histograms: Seq[HistogramBucket] = Seq(bucket1)
    Mockito.when(window.histogramBucketStore.sliceUntil(Matchers.eq(metricKey), Matchers.any[Long], Matchers.eq(1 millis))).thenReturn(Future(histograms))

    //mock summary that match histogram from bucket1
    val summary = StatisticSummary(15000 * 1, 50, 80, 90, 95, 99, 100, 1, 100, 100, 50.5)
    Mockito.when(window.metaStore.getLastProcessedTimestamp(metricKey)).thenReturn(Future(15000L))
    Mockito.when(window.histogramBucketStore.remove("metrickA", 1 millis, Seq(bucket1))).thenReturn(Future {})

    //call method to test
    val f = window.process(metricKey, executionTime)
    Await.result(f, 5 seconds)

    //verify that not store any temporal histogram
    //Mockito.verify(window.histogramBucketStore, Mockito.never()).store(metricKey, windowDuration, Seq())

    //verify that not store any summary
    Mockito.verify(window.statisticSummaryStore, Mockito.never()).store(metricKey, windowDuration, Seq())

    //verify removal of previous buckets
    println("verify remove!")
    Mockito.verify(window.histogramBucketStore).remove("metrickA", 1 millis, Seq(bucket1))
  }

  test("Empty temporal data should do nothing") {
    val windowDuration: FiniteDuration = 30 seconds

    val window = getMockedWindow(windowDuration, 1 millis)

    val metricKey: String = "metrickA"

    //mock temporal data to be empty
    Mockito.when(window.histogramBucketStore.sliceUntil(Matchers.eq(metricKey), Matchers.any[Long], Matchers.eq(1 millis))).thenReturn(Future(Nil))

    Mockito.when(window.metaStore.getLastProcessedTimestamp(metricKey)).thenReturn(Future(-Long.MaxValue))

    //call method to test
    val f = window.process(metricKey, System.currentTimeMillis())
    Await.result(f, 5 seconds)

    //verify that not store any temporal histogram
    Mockito.verify(window.histogramBucketStore, Mockito.never()).store(metricKey, windowDuration, Seq())

    //verify that not store any summary
    Mockito.verify(window.statisticSummaryStore, Mockito.never()).store(metricKey, windowDuration, Seq())

    //verify that not remove anything
    Mockito.verify(window.histogramBucketStore, Mockito.never()).remove("metrickA", 1 millis, Seq())
  }
}

