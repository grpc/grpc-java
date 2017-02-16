/*
 * Copyright 2017, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.services;

import static org.junit.Assert.assertEquals;

import com.google.instrumentation.common.Duration;
import com.google.instrumentation.common.Timestamp;
import com.google.instrumentation.stats.DistributionAggregation;
import com.google.instrumentation.stats.DistributionAggregation.Range;
import com.google.instrumentation.stats.DistributionAggregationDescriptor;
import com.google.instrumentation.stats.IntervalAggregation;
import com.google.instrumentation.stats.IntervalAggregation.Interval;
import com.google.instrumentation.stats.IntervalAggregationDescriptor;
import com.google.instrumentation.stats.MeasurementDescriptor;
import com.google.instrumentation.stats.MeasurementDescriptor.BasicUnit;
import com.google.instrumentation.stats.MeasurementDescriptor.MeasurementUnit;
import com.google.instrumentation.stats.Tag;
import com.google.instrumentation.stats.TagKey;
import com.google.instrumentation.stats.TagValue;
import com.google.instrumentation.stats.View.DistributionView;
import com.google.instrumentation.stats.View.IntervalView;
import com.google.instrumentation.stats.ViewDescriptor.DistributionViewDescriptor;
import com.google.instrumentation.stats.ViewDescriptor.IntervalViewDescriptor;
import com.google.instrumentation.stats.proto.CensusProto;
import io.grpc.instrumentation.v1alpha.CanonicalRpcStats;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link MonitoringUtil}. */
@RunWith(JUnit4.class)
public class MonitoringUtilTest {
  @Test
  public void buildCanonicalRpcStatsViewForDistributionView() throws Exception {
    assertEquals(
        distributionCanonicalStatsProto,
        MonitoringUtil.buildCanonicalRpcStatsView(distributionView));
  }

  @Test
  public void buildCanonicalRpcStatsViewForIntervalView() throws Exception {
    assertEquals(
        intervalCanonicalStatsProto,
        MonitoringUtil.buildCanonicalRpcStatsView(intervalView));
  }

  @Test
  public void serializeMeasurementDescriptor() throws Exception {
    assertEquals(
        measurementDescriptorProto,
        MonitoringUtil.serializeMeasurementDescriptor(measurementDescriptor));
  }

  @Test
  public void serializeMeasurementUnit() throws Exception {
    assertEquals(
        measurementUnitProto, MonitoringUtil.serializeMeasurementUnit(measurementUnit));
  }

  @Test
  public void serializeViewDescriptorForDistributionView() throws Exception {
    assertEquals(
        distributionViewDescriptorProto,
        MonitoringUtil.serializeViewDescriptor(distributionViewDescriptor));
  }

  @Test
  public void serializeViewDescriptorForIntervalView() throws Exception {
    assertEquals(
        intervalViewDescriptorProto,
        MonitoringUtil.serializeViewDescriptor(intervalViewDescriptor));
  }

  @Test
  public void serializeDistributionAggregationDescriptor() throws Exception {
    assertEquals(
        distributionAggregationDescriptorProto,
        MonitoringUtil.serializeDistributionAggregationDescriptor(
            distributionAggregationDescriptor));
  }

  @Test
  public void serializeIntervalAggregationDescriptor() throws Exception {
    assertEquals(
        intervalAggregationDescriptorProto,
        MonitoringUtil.serializeIntervalAggregationDescriptor(
            intervalAggregationDescriptor));
  }

  @Test
  public void serializeDuration() throws Exception {
    assertEquals(durationProto, MonitoringUtil.serializeDuration(duration));
  }

  @Test
  public void serializeViewWithDistributionView() throws Exception {
    assertEquals(
        viewWithDistributionViewProto, MonitoringUtil.serializeView(distributionView));
  }

  @Test
  public void serializeViewWithIntervalView() throws Exception {
    assertEquals(viewWithIntervalViewProto, MonitoringUtil.serializeView(intervalView));
  }

  @Test
  public void serializeDistributionView() throws Exception {
    assertEquals(
        distributionViewProto,
        MonitoringUtil.serializeDistributionView(distributionView));
  }

  @Test
  public void serializeTimestamp() throws Exception {
    assertEquals(startTimestampProto, MonitoringUtil.serializeTimestamp(startTimestamp));
  }

  @Test
  public void serializeDistributionAggregation() throws Exception {
    assertEquals(
        distributionAggregationProto,
        MonitoringUtil.serializeDistributionAggregation(distributionAggregation));
  }

  @Test
  public void serializeRange() throws Exception {
    assertEquals(rangeProto, MonitoringUtil.serializeRange(range));
  }

  @Test
  public void serializeTag() throws Exception {
    assertEquals(tagProto, MonitoringUtil.serializeTag(tag));
  }

  @Test
  public void serializeIntervalView() throws Exception {
    assertEquals(intervalViewProto, MonitoringUtil.serializeIntervalView(intervalView));
  }

  @Test
  public void serializeIntervalAggregation() throws Exception {
    assertEquals(
        intervalAggregationProto,
        MonitoringUtil.serializeIntervalAggregation(intervalAggregation));
  }

  @Test
  public void serializeInterval() throws Exception {
    assertEquals(intervalProto, MonitoringUtil.serializeInterval(interval));
  }

  private int measurementUnitPower = -3;
  private MeasurementUnit measurementUnit =
      MeasurementUnit.create(
          measurementUnitPower,
          Arrays.asList(BasicUnit.SCALAR),
          Arrays.asList(BasicUnit.SECONDS, BasicUnit.SECONDS));
  private CensusProto.MeasurementDescriptor.MeasurementUnit measurementUnitProto =
      CensusProto.MeasurementDescriptor.MeasurementUnit.newBuilder()
          .setPower10(measurementUnitPower)
          .addNumerators(MonitoringUtil.serializeBasicUnit(BasicUnit.SCALAR))
          .addDenominators(MonitoringUtil.serializeBasicUnit(BasicUnit.SECONDS))
          .addDenominators(MonitoringUtil.serializeBasicUnit(BasicUnit.SECONDS))
          .build();

  private String measurementDescriptorName = "measurement descriptor name";
  private String measurementDescriptorDescription = "measurement descriptor description";
  private MeasurementDescriptor measurementDescriptor =
      MeasurementDescriptor.create(
          measurementDescriptorName, measurementDescriptorDescription, measurementUnit);
  private CensusProto.MeasurementDescriptor measurementDescriptorProto =
      CensusProto.MeasurementDescriptor.newBuilder()
          .setName(measurementDescriptorName)
          .setDescription(measurementDescriptorDescription)
          .setUnit(measurementUnitProto)
          .build();

  private long startSeconds = 1L;
  private int startNanos = 1;
  private long endSeconds = 100000L;
  private int endNanos = 9999;
  private Timestamp startTimestamp = Timestamp.create(startSeconds, startNanos);
  private Timestamp endTimestamp = Timestamp.create(endSeconds, endNanos);
  private CensusProto.Timestamp startTimestampProto =
      CensusProto.Timestamp.newBuilder().setSeconds(startSeconds).setNanos(startNanos).build();
  private CensusProto.Timestamp endTimestampProto =
      CensusProto.Timestamp.newBuilder().setSeconds(endSeconds).setNanos(endNanos).build();

  private String tagKey = "tag key";
  private String tagValue = "tag value";
  private Tag tag = Tag.create(TagKey.create(tagKey), TagValue.create(tagValue));
  private CensusProto.Tag tagProto =
      CensusProto.Tag.newBuilder().setKey(tagKey).setValue(tagValue).build();

  private double rangeMin = 0.1;
  private double rangeMax = 999.9;
  private Range range = Range.create(rangeMin, rangeMax);
  private CensusProto.DistributionAggregation.Range rangeProto =
      CensusProto.DistributionAggregation.Range.newBuilder()
          .setMin(rangeMin)
          .setMax(rangeMax)
          .build();

  private long distributionAggregationCount = 100L;
  private double distributionAggregationMean = 55.1;
  private double distributionAggregationSum = 4098.5;
  private long bucketCount = 11L;
  private DistributionAggregation distributionAggregation =
      DistributionAggregation.create(
          distributionAggregationCount,
          distributionAggregationMean,
          distributionAggregationSum,
          range,
          Arrays.asList(tag),
          Arrays.asList(bucketCount));
  private CensusProto.DistributionAggregation distributionAggregationProto =
      CensusProto.DistributionAggregation.newBuilder()
          .setCount(distributionAggregationCount)
          .setMean(distributionAggregationMean)
          .setSum(distributionAggregationSum)
          .setRange(rangeProto)
          .addAllBucketCounts(Arrays.asList(bucketCount))
          .addTags(tagProto)
          .build();

  private double bucketBoundary = 14.0;
  private DistributionAggregationDescriptor distributionAggregationDescriptor =
      DistributionAggregationDescriptor.create(Arrays.asList(bucketBoundary));
  private CensusProto.DistributionAggregationDescriptor distributionAggregationDescriptorProto =
      CensusProto.DistributionAggregationDescriptor.newBuilder()
          .addBucketBounds(bucketBoundary)
          .build();

  private String distributionViewName = "distribution view name";
  private String distributionViewDescription = "distribution view description";
  private DistributionViewDescriptor distributionViewDescriptor =
      DistributionViewDescriptor.create(
          distributionViewName,
          distributionViewDescription,
          measurementDescriptor,
          distributionAggregationDescriptor,
          Arrays.asList(TagKey.create(tagKey)));
  private CensusProto.ViewDescriptor distributionViewDescriptorProto =
      CensusProto.ViewDescriptor.newBuilder()
          .setName(distributionViewName)
          .setDescription(distributionViewDescription)
          .setMeasurementDescriptorName(measurementDescriptorName)
          .setDistributionAggregation(distributionAggregationDescriptorProto)
          .addTagKeys(tagKey)
          .build();

  private DistributionView distributionView =
      DistributionView.create(
          distributionViewDescriptor,
          Arrays.asList(distributionAggregation),
          startTimestamp,
          endTimestamp);
  private CensusProto.DistributionView distributionViewProto =
      CensusProto.DistributionView.newBuilder()
          .addAggregations(distributionAggregationProto)
          // TODO(ericgribkoff) Re-enable once getter methods are public in instrumentation.
          //.setStart(startTimestampProto)
          //.setEnd(endTimestampProto)
          .build();
  private CensusProto.View viewWithDistributionViewProto =
      CensusProto.View.newBuilder()
          .setViewName(distributionViewName)
          .setDistributionView(distributionViewProto)
          .build();
  private CanonicalRpcStats.View distributionCanonicalStatsProto =
      CanonicalRpcStats.View.newBuilder()
          .setMeasurementDescriptor(measurementDescriptorProto)
          .setViewDescriptor(distributionViewDescriptorProto)
          .setView(viewWithDistributionViewProto)
          .build();

  private long durationSeconds = 100L;
  private int durationNanos = 9999;
  private Duration duration = Duration.create(durationSeconds, durationNanos);
  private CensusProto.Duration durationProto =
      CensusProto.Duration.newBuilder().setSeconds(durationSeconds).setNanos(durationNanos).build();

  private int numSubIntervals = 2;
  private IntervalAggregationDescriptor intervalAggregationDescriptor =
      IntervalAggregationDescriptor.create(numSubIntervals, Arrays.asList(duration));
  private CensusProto.IntervalAggregationDescriptor intervalAggregationDescriptorProto =
      CensusProto.IntervalAggregationDescriptor.newBuilder()
          .setNSubIntervals(numSubIntervals)
          .addIntervalSizes(durationProto)
          .build();

  private String intervalViewName = "interval view name";
  private String intervalViewDescription = "interval view description";
  private IntervalViewDescriptor intervalViewDescriptor =
      IntervalViewDescriptor.create(
          intervalViewName,
          intervalViewDescription,
          measurementDescriptor,
          intervalAggregationDescriptor,
          Arrays.asList(TagKey.create(tagKey)));
  private CensusProto.ViewDescriptor intervalViewDescriptorProto =
      CensusProto.ViewDescriptor.newBuilder()
          .setName(intervalViewName)
          .setDescription(intervalViewDescription)
          .setMeasurementDescriptorName(measurementDescriptorName)
          .setIntervalAggregation(intervalAggregationDescriptorProto)
          .addTagKeys(tagKey)
          .build();

  private double intervalCount = 6.0;
  private double intervalSum = 98.5;
  private Interval interval = Interval.create(duration, intervalCount, intervalSum);
  private CensusProto.IntervalAggregation.Interval intervalProto =
      CensusProto.IntervalAggregation.Interval.newBuilder()
          .setIntervalSize(durationProto)
          .setCount(intervalCount)
          .setSum(intervalSum)
          .build();

  private IntervalAggregation intervalAggregation =
      IntervalAggregation.create(Arrays.asList(tag), Arrays.asList(interval));
  private CensusProto.IntervalAggregation intervalAggregationProto =
      CensusProto.IntervalAggregation.newBuilder()
          .addIntervals(intervalProto)
          .addTags(tagProto)
          .build();

  private IntervalView intervalView =
      IntervalView.create(intervalViewDescriptor, Arrays.asList(intervalAggregation));
  private CensusProto.IntervalView intervalViewProto =
      CensusProto.IntervalView.newBuilder().addAggregations(intervalAggregationProto).build();
  private CensusProto.View viewWithIntervalViewProto =
      CensusProto.View.newBuilder()
          .setViewName(intervalViewName)
          .setIntervalView(intervalViewProto)
          .build();
  private CanonicalRpcStats.View intervalCanonicalStatsProto =
      CanonicalRpcStats.View.newBuilder()
          .setMeasurementDescriptor(measurementDescriptorProto)
          .setViewDescriptor(intervalViewDescriptorProto)
          .setView(viewWithIntervalViewProto)
          .build();
}
