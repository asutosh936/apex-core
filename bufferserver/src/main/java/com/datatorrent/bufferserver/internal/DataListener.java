/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.bufferserver.internal;

import com.datatorrent.bufferserver.util.BitVector;

import java.util.Collection;

/**
 *
 * Waits for data to be added to the buffer server and then acts on it<p>
 * <br>
 * The behavior upon data addition is customizable
 *
 * @author chetan
 * @since 0.3.2
 */
public interface DataListener
{
  public static final BitVector NULL_PARTITION = new BitVector(0, 0);

  /**
   */
  public void addedData();

  /**
   *
   * @param partitions
   * @return int
   */
  public int getPartitions(Collection<BitVector> partitions);

}
