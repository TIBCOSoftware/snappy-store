/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.gemstone.gemfire.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;

/**
 * Used to carry around enough information of a region so as to recreate the
 * region on the receiver node if required.
 * 
 * @author swale
 * @since 7.0
 */
public final class RegionInfoShip implements DataSerializable {

  private static final long serialVersionUID = 1L;

  /** used for non-PR regions */
  String fullPath;

  /** used for PR regions */
  int prId;

  /** used for Buckets */
  int bucketId;

  /**
   * the type of the Region: one of {@link #IS_RR}, {@link #IS_PR},
   * {@link #IS_BUC}
   */
  byte type;

  // possible values for the type field
  /** denotes a regular local or distributed region */
  static final byte IS_RR = 1;
  /** denotes partitioned region */
  static final byte IS_PR = 2;
  /** denotes a ProxyBucketRegion or BucketRegion */
  static final byte IS_BUC = 3;

  public RegionInfoShip() {
  }

  public RegionInfoShip(final Object region) {
    init(region);
  }

  public RegionInfoShip(final ProxyBucketRegion pbr) {
    init(pbr);
  }

  void init(final Object region) {
    if (region instanceof ProxyBucketRegion) {
      init((ProxyBucketRegion)region);
    }
    else {
      init((LocalRegion)region);
    }
  }

  void init(final ProxyBucketRegion pbr) {
    this.type = IS_BUC;
    this.fullPath = null;
    this.prId = pbr.getPartitionedRegion().getPRId();
    this.bucketId = pbr.getBucketId();
  }

  public void init(final LocalRegion region) {
    if (region instanceof BucketRegion) {
      BucketRegion br = (BucketRegion)region;
      this.type = IS_BUC;
      this.fullPath = null;
      this.prId = br.getPartitionedRegion().getPRId();
      this.bucketId = br.getId();
    }
    else if (region instanceof PartitionedRegion) {
      this.type = IS_PR;
      this.fullPath = null;
      this.prId = ((PartitionedRegion)region).getPRId();
      this.bucketId = -1;
    }
    else {
      this.type = IS_RR;
      this.fullPath = region.getFullPath();
      this.prId = -1;
      this.bucketId = -1;
    }
  }

  void init(byte type, String fullPath, int prId, int bucketId) {
    this.type = type;
    this.fullPath = fullPath;
    this.prId = prId;
    this.bucketId = bucketId;
  }

  public final Object lookupRegionObject(final GemFireCacheImpl cache) {
    switch (this.type) {
      case IS_BUC:
        try {
          PartitionedRegion pr = PartitionedRegion.getPRFromId(this.prId);
          if (pr != null) {
            final ProxyBucketRegion[] pbrs = pr.getRegionAdvisor()
                .getProxyBucketArray();
            if (pbrs != null) {
              return pbrs[this.bucketId];
            }
          }
        } catch (PRLocallyDestroyedException prlde) {
          // return null below for this case
        } catch (RegionDestroyedException rde) {
          // return null below for this case
        }
        return null;
      case IS_RR:
        return cache.getRegionByPath(this.fullPath, false);
      case IS_PR:
        try {
          return PartitionedRegion.getPRFromId(this.prId);
        } catch (PRLocallyDestroyedException prlde) {
          return null;
        } catch (RegionDestroyedException rde) {
          return null;
        }
      default:
        throw new InternalGemFireError(
            "RegionInfoShip#lookupRegionObject: unknown region type="
                + this.type);
    }
  }

  public final LocalRegion lookupRegion(final GemFireCacheImpl cache) {
    switch (this.type) {
      case IS_BUC:
        try {
          PartitionedRegion pr = PartitionedRegion.getPRFromId(this.prId);
          if (pr != null) {
            final ProxyBucketRegion[] pbrs = pr.getRegionAdvisor()
                .getProxyBucketArray();
            if (pbrs != null) {
              return pbrs[this.bucketId].getCreatedBucketRegion();
            }
          }
        } catch (PRLocallyDestroyedException | RegionDestroyedException |
            CancelException e) {
          // return null below for this case
        }
        return null;
      case IS_RR:
        try {
          return cache != null ? cache.getRegionByPath(
              this.fullPath, false) : null;
        } catch (RegionDestroyedException | CancelException e) {
          return null;
        }
      case IS_PR:
        try {
          return PartitionedRegion.getPRFromId(this.prId);
        } catch (PRLocallyDestroyedException | RegionDestroyedException |
            CancelException e) {
          return null;
        }
      default:
        throw new InternalGemFireError(
            "RegionInfoShip#lookupRegion: unknown region type=" + this.type);
    }
  }

  /**
   * @see DataSerializable#toData(DataOutput)
   */
  public void toData(DataOutput out) throws IOException {
    out.writeByte(this.type);
    switch (this.type) {
      case IS_BUC:
        InternalDataSerializer.writeUnsignedVL(this.prId, out);
        InternalDataSerializer.writeSignedVL(this.bucketId, out);
        break;
      case IS_RR:
        DataSerializer.writeString(this.fullPath, out);
        break;
      case IS_PR:
        InternalDataSerializer.writeUnsignedVL(this.prId, out);
        break;
      default:
        throw new InternalGemFireError(
            "RegionInfoShip#toData: unknown type of region: " + this.type);
    }
  }

  /**
   * @see DataSerializable#fromData(DataInput)
   */
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    final byte type = in.readByte();
    switch (type) {
      case IS_BUC:
        int prId = (int)InternalDataSerializer.readUnsignedVL(in);
        int bucketId = (int)InternalDataSerializer.readSignedVL(in);
        init(IS_BUC, null, prId, bucketId);
        break;
      case IS_RR:
        init(IS_RR, DataSerializer.readString(in), -1, -1);
        break;
      case IS_PR:
        init(IS_PR, null,
            (int)InternalDataSerializer.readUnsignedVL(in), -1);
        break;
      default:
        throw new InternalGemFireError(
            "RegionInfoShip#fromData: unknown type of region: " + type);
    }
  }

  // the ability to lookup against regions is deliberate
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="EQ_CHECK_FOR_OPERAND_NOT_COMPATIBLE_WITH_THIS")
  @Override
  public boolean equals(Object o) {
    if (o instanceof RegionInfoShip) {
      final String path = this.fullPath;
      RegionInfoShip ri = (RegionInfoShip)o;
      return path != null ? path.equals(ri.fullPath) : (this.type == ri.type
          && this.bucketId == ri.bucketId && this.prId == ri.prId);
    }
    else if (o instanceof BucketRegion) {
      final BucketRegion br = (BucketRegion)o;
      return (this.type == IS_BUC && this.bucketId == br.getId()
          && this.prId == br.getPartitionedRegion().getPRId());
    }
    else if (o instanceof PartitionedRegion) {
      return (this.type == IS_PR && this.prId == ((PartitionedRegion)o)
          .getPRId());
    }
    else if (o instanceof ProxyBucketRegion) {
      final ProxyBucketRegion pbr = (ProxyBucketRegion)o;
      return (this.type == IS_BUC && this.bucketId == pbr.getId()
          && this.prId == pbr.getPartitionedRegion().getPRId());
    }
    else if (o instanceof LocalRegion) {
      final String path = this.fullPath;
      if (path != null) {
        return path.equals(((LocalRegion)o).getFullPath());
      }
    }
    return false;
  }

  public static int getHashCode(final int prId, final int bucketId) {
    if (bucketId >= 0) {
      return ((IS_BUC << 8) ^ (prId << 16) ^ bucketId);
    }
    else {
      return ((IS_PR << 8) ^ (prId << 16));
    }
  }

  @Override
  public int hashCode() {
    return this.fullPath != null ? this.fullPath.hashCode() : getHashCode(
        this.prId, this.bucketId);
  }

  @Override
  public String toString() {
    PartitionedRegion pr;
    switch (this.type) {
      case IS_BUC:
        try {
          pr = PartitionedRegion.getPRFromId(this.prId);
        } catch (PRLocallyDestroyedException prlde) {
          pr = null;
        } catch (RegionDestroyedException rde) {
          pr = null;
        }
        return "Bucket(prId=" + this.prId + (pr != null ? "{" + pr.getFullPath()
            + '}' : "") + ",bucketId=" + this.bucketId + ")";
      case IS_RR:
        return "Region{" + this.fullPath + '}';
      case IS_PR:
        try {
          pr = PartitionedRegion.getPRFromId(this.prId);
        } catch (PRLocallyDestroyedException prlde) {
          pr = null;
        } catch (RegionDestroyedException rde) {
          pr = null;
        }
        return "PartitionedRegion(prId=" + this.prId
            + (pr != null ? "{" + pr.getFullPath() + "})" : ")");
      default:
        return "REGIONINFO_UNKNOWN_TYPE(" + this.type + ")";
    }
  }
}
