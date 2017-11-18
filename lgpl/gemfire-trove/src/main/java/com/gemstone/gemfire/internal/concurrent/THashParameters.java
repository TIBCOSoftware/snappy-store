/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.gemstone.gemfire.internal.concurrent;

import com.gemstone.gnu.trove.HashingStats;
import com.gemstone.gnu.trove.TObjectHashingStrategy;

/**
 * Holds various parameters used for hashing.
 *
 * @author swale
 * @since gfxd 1.0
 */
@SuppressWarnings("serial")
public class THashParameters {

  public static final ObjectHashing DEFAULT_HASHING = new ObjectHashing();

  /**
   * Determines how full the internal table can become before rehashing is
   * required. This must be a value in the range: 0.0 < loadFactor < 1.0.
   */
  protected final float loadFactor;

  /**
   * the strategy used to hash objects, if any, in this collection.
   */
  protected final transient TObjectHashingStrategy hashingStrategy;

  /**
   * optional statistics object to track number of hash collisions and time
   * spent probing based on hash collisions
   */
  protected final transient HashingStats stats;

  protected THashParameters(float loadFactor, TObjectHashingStrategy strategy,
      HashingStats stats) {
    this.loadFactor = loadFactor;
    this.hashingStrategy = strategy != null ? strategy : DEFAULT_HASHING;
    this.stats = stats;
  }

  /**
   * Applies a supplemental hash function to a given hashCode, which defends
   * against poor quality hash functions. This is critical because
   * ConcurrentHashMap uses power-of-two length hash tables, that otherwise
   * encounter collisions for hashCodes that do not differ in lower or upper
   * bits.
   */
  static int hash(int h) {
    // Spread bits to regularize both segment and index locations,
    // using variant of single-word Wang/Jenkins hash.
    h += (h << 15) ^ 0xffffcd7d;
    h ^= (h >>> 10);
    h += (h << 3);
    h ^= (h >>> 6);
    h += (h << 2) + (h << 14);
    return h ^ (h >>> 16);
  }

  static int computeHashCode(final Object o,
      final TObjectHashingStrategy hashingStrategy) {
    return hash(hashingStrategy.computeHashCode(o)) & 0x7fffffff;
  }

  public static final class ObjectHashing implements TObjectHashingStrategy {

    private static final long serialVersionUID = -2248161209573646282L;

    @Override
    public int computeHashCode(Object o) {
      return o != null ? o.hashCode() : 0;
    }

    @Override
    public boolean equals(Object o1, Object o2) {
      return o1.equals(o2);
    }
  }
}
