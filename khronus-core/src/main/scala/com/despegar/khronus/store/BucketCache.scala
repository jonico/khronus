package com.despegar.khronus.store

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicLong, AtomicReference }

import com.despegar.khronus.model._
import com.despegar.khronus.util.log.Logging
import com.despegar.khronus.util.{ Measurable, Settings }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

trait BucketCacheSupport[T <: Bucket] {
  val bucketCache: BucketCache[T]
}

trait BucketCache[T <: Bucket] extends Logging with Measurable {
  val cachesByMetric: TrieMap[Metric, MetricBucketCache[T]]
  val nCachedMetrics: AtomicLong
  val lastKnownTick: AtomicReference[Tick]
  private val enabled = Settings.BucketCache.Enabled

  def markProcessedTick(metric: Metric, tick: Tick): Unit = if (enabled) {
    val previousKnownTick = lastKnownTick.getAndSet(tick)
    if (previousKnownTick != tick && previousKnownTick != null) {
      cachesByMetric.keySet.foreach { metric ⇒
        if (noCachedBucketFor(metric, previousKnownTick.bucketNumber)) {
          incrementCounter("bucketCache.noMetricAffinity")
          cleanCache(metric)
        }
      }
    }
  }

  def multiSet(metric: Metric, fromBucketNumber: BucketNumber, toBucketNumber: BucketNumber, buckets: Seq[T]): Unit = {
    if (isEnabledFor(metric) && (toBucketNumber.number - fromBucketNumber.number - 1) <= Settings.BucketCache.MaxStore) {
      log.debug(s"Caching ${buckets.length} buckets of ${fromBucketNumber.duration} for $metric")
      metricCacheOf(metric).map { cache ⇒
        buckets.foreach { bucket ⇒
          val previousBucket = cache.putIfAbsent(bucket.bucketNumber, bucket)
          if (previousBucket != null) {
            incrementCounter("bucketCache.overrideWarning")
            log.warn("More than one cached Bucket per BucketNumber. Overriding it to leave just one of them.")
          }
        }
        fillEmptyBucketsIfNecessary(metric, cache, fromBucketNumber, toBucketNumber)
      }
    }
  }

  def multiGet(metric: Metric, fromBucketNumber: BucketNumber, toBucketNumber: BucketNumber): Option[BucketSlice[T]] = {
    if (!enabled || !Settings.BucketCache.IsEnabledFor(metric.mtype) || isRawTimeWindow(fromBucketNumber)) return None
    val expectedBuckets = toBucketNumber.number - fromBucketNumber.number
    val slice: Option[BucketSlice[T]] = metricCacheOf(metric).flatMap { cache ⇒
      val buckets = takeRecursive(cache, fromBucketNumber, toBucketNumber)
      if (buckets.size == expectedBuckets) {
        cacheHit(metric, buckets, fromBucketNumber, toBucketNumber)
      } else {
        None
      }
    }
    if (slice.isEmpty) {
      cacheMiss(metric, expectedBuckets, fromBucketNumber, toBucketNumber)
    }
    slice
  }

  private def cleanCache(metric: Metric) = {
    log.debug(s"Lose $metric affinity. Cleaning its bucket cache")
    cachesByMetric.remove(metric)
    nCachedMetrics.decrementAndGet()
  }

  private def noCachedBucketFor(metric: Metric, bucketNumber: BucketNumber): Boolean = {
    !metricCacheOf(metric).map(cache ⇒ cache.keySet().exists(a ⇒ a.contains(bucketNumber))).getOrElse(false)
  }

  private def metricCacheOf(metric: Metric): Option[MetricBucketCache[T]] = {
    val cache = cachesByMetric.get(metric)
    if (cache.isDefined) {
      cache
    } else {
      if (nCachedMetrics.incrementAndGet() > Settings.BucketCache.MaxMetrics) {
        nCachedMetrics.decrementAndGet()
        None
      } else {
        val previous = cachesByMetric.putIfAbsent(metric, buildCache())

        if (previous != null) previous else cache
      }
    }
  }

  def buildCache(): MetricBucketCache[T]

  private def isEnabledFor(metric: Metric): Boolean = {
    enabled && Settings.BucketCache.IsEnabledFor(metric.mtype)
  }

  @tailrec
  private def fillEmptyBucketsIfNecessary(metric: Metric, cache: MetricBucketCache[T], bucketNumber: BucketNumber, until: BucketNumber): Unit = {
    if (bucketNumber < until) {
      val previous = cache.putIfAbsent(bucketNumber, cache.buildEmptyBucket())
      if (previous == null) {
        log.debug(s"Filling empty bucket $bucketNumber of metric $metric")
      }
      fillEmptyBucketsIfNecessary(metric, cache, bucketNumber + 1, until)
    }
  }

  @tailrec
  private def takeRecursive(metricCache: MetricBucketCache[_ <: Bucket], bucketNumber: BucketNumber, until: BucketNumber, buckets: List[(BucketNumber, Any)] = List[(BucketNumber, Any)]()): List[(BucketNumber, Any)] = {
    if (bucketNumber < until) {
      val bucket: Bucket = metricCache.remove(bucketNumber)
      takeRecursive(metricCache, bucketNumber + 1, until, if (bucket != null) buckets :+ (bucketNumber, bucket) else buckets)
    } else {
      buckets
    }
  }

  private def isRawTimeWindow(fromBucketNumber: BucketNumber): Boolean = {
    fromBucketNumber.duration == Settings.Window.RawDuration
  }

  private def cacheMiss(metric: Metric, expectedBuckets: Long, fromBucketNumber: BucketNumber, toBucketNumber: BucketNumber): Option[BucketSlice[T]] = {
    log.debug(s"CacheMiss of ${expectedBuckets} buckets for $metric between $fromBucketNumber and $toBucketNumber")
    incrementCounter("bucketCache.miss")
    None
  }

  private def cacheHit(metric: Metric, buckets: List[(BucketNumber, Any)], fromBucketNumber: BucketNumber, toBucketNumber: BucketNumber): Option[BucketSlice[T]] = {
    log.debug(s"CacheHit of ${buckets.size} buckets for $metric between $fromBucketNumber and $toBucketNumber")
    incrementCounter("bucketCache.hit")
    val noEmptyBuckets: List[(BucketNumber, Any)] = buckets.filterNot(bucket ⇒ bucket._2.isInstanceOf[EmptyBucket])
    if (noEmptyBuckets.isEmpty) {
      incrementCounter("bucketCache.hit.empty")
    }
    Some(BucketSlice(noEmptyBuckets.map { bucket ⇒
      BucketResult(bucket._1.startTimestamp(), new LazyBucket(bucket._2.asInstanceOf[T]))
    }))
  }

}

object InMemoryCounterBucketCache extends BucketCache[CounterBucket] {
  override val cachesByMetric: TrieMap[Metric, MetricBucketCache[CounterBucket]] = new TrieMap[Metric, MetricBucketCache[CounterBucket]]()
  override val nCachedMetrics = new AtomicLong(0)
  override val lastKnownTick = new AtomicReference[Tick]()

  override def buildCache(): MetricBucketCache[CounterBucket] = new CounterMetricBucketCache()

}

object InMemoryHistogramBucketCache extends BucketCache[HistogramBucket] {
  override val cachesByMetric: TrieMap[Metric, MetricBucketCache[HistogramBucket]] = new TrieMap[Metric, MetricBucketCache[HistogramBucket]]()
  override val nCachedMetrics: AtomicLong = new AtomicLong(0)
  override val lastKnownTick: AtomicReference[Tick] = new AtomicReference[Tick]()

  override def buildCache(): MetricBucketCache[HistogramBucket] = new HistogramMetricBucketCache()
}

trait MetricBucketCache[T <: Bucket] {
  def buildEmptyBucket(): T

  protected val cache = new ConcurrentHashMap[BucketNumber, Array[Byte]]()

  def serialize(bucket: T): Array[Byte]

  def deserialize(bytes: Array[Byte], bucketNumber: BucketNumber): T

  def putIfAbsent(bucketNumber: BucketNumber, bucket: T): Bucket = {
    if (bucket.isInstanceOf[EmptyBucket]) {
      val older = cache.putIfAbsent(bucketNumber, Array.empty[Byte])
      checkEmptyBucket(older, bucketNumber)
    } else {
      deserialize(cache.putIfAbsent(bucketNumber, serialize(bucket)), bucketNumber)
    }
  }

  def remove(bucketNumber: BucketNumber): Bucket = {
    val bytes = cache.remove(bucketNumber)
    checkEmptyBucket(bytes, bucketNumber)
  }

  def checkEmptyBucket(bytes: Array[Byte], bucketNumber: BucketNumber): Bucket = {
    if (bytes.length == 0) {
      buildEmptyBucket()
    } else {
      deserialize(bytes, bucketNumber)
    }
  }

  def keySet(): mutable.Set[BucketNumber] = cache.keySet().asScala
}

class CounterMetricBucketCache extends MetricBucketCache[CounterBucket] {
  private val serializer: CounterBucketSerializer = DefaultCounterBucketSerializer

  override def serialize(bucket: CounterBucket): Array[Byte] = serializer.serialize(bucket).array()

  override def deserialize(bytes: Array[Byte], bucketNumber: BucketNumber): CounterBucket = new CounterBucket(bucketNumber, serializer.deserializeCounts(bytes))

  override def buildEmptyBucket(): CounterBucket = EmptyCounterBucket
}

class HistogramMetricBucketCache extends MetricBucketCache[HistogramBucket] {
  private val histogramSerializer: HistogramSerializer = DefaultHistogramSerializer

  override def serialize(bucket: HistogramBucket): Array[Byte] = histogramSerializer.serialize(bucket.histogram).array()

  override def deserialize(bytes: Array[Byte], bucketNumber: BucketNumber): HistogramBucket = new HistogramBucket(bucketNumber, histogramSerializer.deserialize(ByteBuffer.wrap(bytes)))

  override def buildEmptyBucket(): HistogramBucket = EmptyHistogramBucket
}

object EmptyHistogramBucket extends EmptyHistogramBucket

class EmptyHistogramBucket extends HistogramBucket(UndefinedBucketNumber, null) with EmptyBucket {
  override val summary = null
}

object EmptyCounterBucket extends EmptyCounterBucket

class EmptyCounterBucket extends CounterBucket(UndefinedBucketNumber, 0) with EmptyBucket {
  override val summary = null
}

trait EmptyBucket
//
//object EmptyBucket extends EmptyBucket
//
//class EmptyBucket extends Bucket(UndefinedBucketNumber) {
//  val summary = null
//}

object UndefinedBucketNumber extends BucketNumber(-1, null) {
  override def toString = {
    "UndefinedBucketNumber"
  }
}
