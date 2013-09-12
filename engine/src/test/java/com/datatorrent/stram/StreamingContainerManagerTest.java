/**
 * Copyright (c) 2012-2013 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import junit.framework.Assert;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputByteBuffer;
import org.junit.Test;

import com.datatorrent.stram.codec.DefaultStatefulStreamCodec;
import com.datatorrent.stram.engine.DefaultUnifier;
import com.datatorrent.stram.engine.GenericTestOperator;
import com.datatorrent.stram.engine.Node;
import com.datatorrent.stram.engine.TestGeneratorInputOperator;
import com.datatorrent.stram.HdfsStorageAgent;
import com.datatorrent.stram.OperatorDeployInfo;
import com.datatorrent.stram.StramChildAgent;
import com.datatorrent.stram.StreamingContainerManager;
import com.datatorrent.stram.OperatorDeployInfo.InputDeployInfo;
import com.datatorrent.stram.OperatorDeployInfo.OperatorType;
import com.datatorrent.stram.OperatorDeployInfo.OutputDeployInfo;
import com.datatorrent.stram.StramChildAgent.ContainerStartRequest;
import com.datatorrent.stram.StreamingContainerManager.ContainerResource;
import com.datatorrent.stram.StreamingContainerUmbilicalProtocol.ContainerHeartbeatResponse;
import com.datatorrent.stram.plan.PhysicalPlanTest;
import com.datatorrent.stram.plan.PhysicalPlanTest.PartitioningTestOperator;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.LogicalPlan.OperatorMeta;
import com.datatorrent.stram.plan.physical.PTContainer;
import com.datatorrent.stram.plan.physical.PTOperator;
import com.datatorrent.stram.plan.physical.PhysicalPlan;
import com.datatorrent.stram.tuple.Tuple;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.datatorrent.api.AttributeMap;
import com.datatorrent.api.Context;
import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.Context.PortContext;
import com.datatorrent.api.DAG.Locality;
import com.datatorrent.api.DAGContext;
import com.datatorrent.api.Operator;

public class StreamingContainerManagerTest {

  @Test
  public void testDeployInfoSerialization() throws Exception {
    OperatorDeployInfo ndi = new OperatorDeployInfo();
    ndi.declaredId = "node1";
    ndi.type = OperatorDeployInfo.OperatorType.GENERIC;
    ndi.id = 1;
    ndi.contextAttributes = new AttributeMap.DefaultAttributeMap(Context.OperatorContext.class);
    ndi.contextAttributes.attr(OperatorContext.SPIN_MILLIS).set(100);

    OperatorDeployInfo.InputDeployInfo input = new OperatorDeployInfo.InputDeployInfo();
    input.declaredStreamId = "streamToNode";
    input.portName = "inputPortNameOnNode";
    input.sourceNodeId = 99;

    ndi.inputs = new ArrayList<OperatorDeployInfo.InputDeployInfo>();
    ndi.inputs.add(input);

    OperatorDeployInfo.OutputDeployInfo output = new OperatorDeployInfo.OutputDeployInfo();
    output.declaredStreamId = "streamFromNode";
    output.portName = "outputPortNameOnNode";

    ndi.outputs = new ArrayList<OperatorDeployInfo.OutputDeployInfo>();
    ndi.outputs.add(output);

    ContainerHeartbeatResponse scc = new ContainerHeartbeatResponse();
    scc.deployRequest = Collections.singletonList(ndi);

    DataOutputByteBuffer out = new DataOutputByteBuffer();
    scc.write(out);

    DataInputByteBuffer in = new DataInputByteBuffer();
    in.reset(out.getData());

    ContainerHeartbeatResponse clone = new ContainerHeartbeatResponse();
    clone.readFields(in);

    Assert.assertNotNull(clone.deployRequest);
    Assert.assertEquals(1, clone.deployRequest.size());
    OperatorDeployInfo ndiClone = clone.deployRequest.get(0);
    Assert.assertEquals("declaredId", ndi.declaredId, ndiClone.declaredId);
    Assert.assertEquals("type", ndi.type, ndiClone.type);

    String nodeToString = ndi.toString();
    Assert.assertTrue(nodeToString.contains(input.portName));
    Assert.assertTrue(nodeToString.contains(output.portName));

    Assert.assertEquals("contextAttributes " + ndiClone.contextAttributes, Integer.valueOf(100), ndiClone.contextAttributes.attr(OperatorContext.SPIN_MILLIS).get());

  }

  @Test
  public void testGenerateDeployInfo() {

    LogicalPlan dag = new LogicalPlan();
    dag.getAttributes().attr(DAGContext.APPLICATION_PATH).set(new File("target", StreamingContainerManagerTest.class.getName()).getAbsolutePath());

    TestGeneratorInputOperator o1 = dag.addOperator("o1", TestGeneratorInputOperator.class);
    GenericTestOperator o2 = dag.addOperator("o2", GenericTestOperator.class);
    GenericTestOperator o3 = dag.addOperator("o3", GenericTestOperator.class);
    GenericTestOperator o4 = dag.addOperator("o4", GenericTestOperator.class);

    dag.addStream("o1.outport", o1.outport, o2.inport1);
    dag.setOutputPortAttribute(o1.outport, PortContext.SPIN_MILLIS, 99);

    dag.addStream("o2.outport1", o2.outport1, o3.inport1)
      .setLocality(Locality.CONTAINER_LOCAL);
    dag.addStream("o3.outport1", o3.outport1, o4.inport1)
      .setLocality(Locality.THREAD_LOCAL);

    dag.getAttributes().attr(LogicalPlan.CONTAINERS_MAX_COUNT).set(2);

    Assert.assertEquals("number operators", 4, dag.getAllOperators().size());
    Assert.assertEquals("number root operators", 1, dag.getRootOperators().size());

    StreamingContainerManager dnm = new StreamingContainerManager(dag);
    Assert.assertEquals("number required containers", 2, dnm.getPhysicalPlan().getContainers().size());

    String container1Id = "container1";
    String container2Id = "container2";

    // o1 needs to be deployed first, regardless in which order they were given
    StramChildAgent sca1 = dnm.assignContainer(new ContainerResource(0, container1Id, "host1", 1024), InetSocketAddress.createUnresolved(container1Id+"Host", 9001));
    Assert.assertEquals("", dnm.getPhysicalPlan().getContainers().get(0), sca1.container);
    Assert.assertEquals("", PTContainer.State.ALLOCATED, sca1.container.getState());
    List<OperatorDeployInfo> c1 = sca1.getDeployInfo();

    Assert.assertEquals("number operators assigned to c1", 1, c1.size());
    OperatorDeployInfo o1DI = getNodeDeployInfo(c1, dag.getMeta(o1));
    Assert.assertNotNull(o1.getName() + " assigned to " + container1Id, o1DI);
    Assert.assertEquals("type " + o1DI, OperatorDeployInfo.OperatorType.INPUT, o1DI.type);
    Assert.assertEquals("inputs " + o1DI.declaredId, 0, o1DI.inputs.size());
    Assert.assertEquals("outputs " + o1DI.declaredId, 1, o1DI.outputs.size());
    Assert.assertNotNull("contextAttributes " + o1DI.declaredId, o1DI.contextAttributes);

    OutputDeployInfo c1o1outport = o1DI.outputs.get(0);
    Assert.assertNotNull("stream connection for container1", c1o1outport);
    Assert.assertEquals("stream connection for container1", "o1.outport", c1o1outport.declaredStreamId);
    Assert.assertEquals("stream connects to upstream host", container1Id + "Host", c1o1outport.bufferServerHost);
    Assert.assertEquals("stream connects to upstream port", 9001, c1o1outport.bufferServerPort);
    Assert.assertNotNull("contextAttributes " + c1o1outport, c1o1outport.contextAttributes);
    Assert.assertEquals("contextAttributes " + c1o1outport,  Integer.valueOf(99), c1o1outport.contextAttributes.attr(PortContext.SPIN_MILLIS).get());

    List<OperatorDeployInfo> c2 = dnm.assignContainer(new ContainerResource(0, container2Id, "host2", 1024), InetSocketAddress.createUnresolved(container2Id+"Host", 9002)).getDeployInfo();
    Assert.assertEquals("number operators assigned to container", 3, c2.size());
    OperatorDeployInfo o2DI = getNodeDeployInfo(c2, dag.getMeta(o2));
    OperatorDeployInfo o3DI = getNodeDeployInfo(c2, dag.getMeta(o3));
    Assert.assertNotNull(o2.getName() + " assigned to " + container2Id, o2DI);
    Assert.assertNotNull(o3.getName() + " assigned to " + container2Id, o3DI);

    // buffer server input o2 from o1
    InputDeployInfo c2o2i1 = getInputDeployInfo(o2DI, "o1.outport");
    Assert.assertNotNull("stream connection for container2", c2o2i1);
    Assert.assertEquals("stream connects to upstream host", container1Id + "Host", c2o2i1.bufferServerHost);
    Assert.assertEquals("stream connects to upstream port", 9001, c2o2i1.bufferServerPort);
    Assert.assertEquals("portName " + c2o2i1, dag.getMeta(o2).getMeta(o2.inport1).getPortName(), c2o2i1.portName);
    Assert.assertNull("partitionKeys " + c2o2i1, c2o2i1.partitionKeys);
    Assert.assertEquals("sourceNodeId " + c2o2i1, o1DI.id, c2o2i1.sourceNodeId);
    Assert.assertEquals("sourcePortName " + c2o2i1, TestGeneratorInputOperator.OUTPUT_PORT, c2o2i1.sourcePortName);
    Assert.assertNotNull("contextAttributes " + c2o2i1, c2o2i1.contextAttributes);

    // inline input o3 from o2
    InputDeployInfo c2o3i1 = getInputDeployInfo(o3DI, "o2.outport1");
    Assert.assertNotNull("input from o2.outport1", c2o3i1);
    Assert.assertEquals("portName " + c2o3i1, GenericTestOperator.IPORT1, c2o3i1.portName);
    Assert.assertNotNull("stream connection for container2", c2o3i1);
    Assert.assertNull("bufferServerHost " + c2o3i1, c2o3i1.bufferServerHost);
    Assert.assertEquals("bufferServerPort " + c2o3i1, 0, c2o3i1.bufferServerPort);
    Assert.assertNull("partitionKeys " + c2o3i1, c2o3i1.partitionKeys);
    Assert.assertEquals("sourceNodeId " + c2o3i1, o2DI.id, c2o3i1.sourceNodeId);
    Assert.assertEquals("sourcePortName " + c2o3i1, GenericTestOperator.OPORT1, c2o3i1.sourcePortName);
    Assert.assertEquals("locality " + c2o3i1, Locality.CONTAINER_LOCAL, c2o3i1.locality);

    // THREAD_LOCAL o4.inport1
    OperatorDeployInfo o4DI = getNodeDeployInfo(c2, dag.getMeta(o4));
    Assert.assertNotNull(o4.getName() + " assigned to " + container2Id, o4DI);
    InputDeployInfo c2o4i1 = getInputDeployInfo(o4DI, "o3.outport1");
    Assert.assertNotNull("input from o3.outport1", c2o4i1);
    Assert.assertEquals("portName " + c2o4i1, GenericTestOperator.IPORT1, c2o4i1.portName);
    Assert.assertNotNull("stream connection for container2", c2o4i1);
    Assert.assertNull("bufferServerHost " + c2o4i1, c2o4i1.bufferServerHost);
    Assert.assertEquals("bufferServerPort " + c2o4i1, 0, c2o4i1.bufferServerPort);
    Assert.assertNull("partitionKeys " + c2o4i1, c2o4i1.partitionKeys);
    Assert.assertEquals("sourceNodeId " + c2o4i1, o3DI.id, c2o4i1.sourceNodeId);
    Assert.assertEquals("sourcePortName " + c2o4i1, GenericTestOperator.OPORT1, c2o4i1.sourcePortName);
    Assert.assertEquals("locality " + c2o4i1, Locality.THREAD_LOCAL, c2o4i1.locality);

  }

  @Test
  public void testStaticPartitioning() {
    LogicalPlan dag = new LogicalPlan();
    dag.getAttributes().attr(DAGContext.APPLICATION_PATH).set(new File("target", StreamingContainerManagerTest.class.getName()).getAbsolutePath());

    GenericTestOperator node1 = dag.addOperator("node1", GenericTestOperator.class);
    PhysicalPlanTest.PartitioningTestOperator node2 = dag.addOperator("node2", PhysicalPlanTest.PartitioningTestOperator.class);
    dag.setAttribute(node2, OperatorContext.INITIAL_PARTITION_COUNT, 3);
    dag.setOutputPortAttribute(node2.outport1, PortContext.QUEUE_CAPACITY, 1111);
    GenericTestOperator node3 = dag.addOperator("node3", GenericTestOperator.class);
    dag.setInputPortAttribute(node3.inport1, PortContext.QUEUE_CAPACITY, 2222);

    LogicalPlan.StreamMeta n1n2 = dag.addStream("n1n2", node1.outport1, node2.inport1);
    LogicalPlan.StreamMeta n2n3 = dag.addStream("n2n3", node2.outport1, node3.inport1);

    dag.setAttribute(LogicalPlan.CONTAINERS_MAX_COUNT, 6);

    StreamingContainerManager dnm = new StreamingContainerManager(dag);
    Assert.assertEquals("number required containers", 6, dnm.getPhysicalPlan().getContainers().size());

    String container1Id = "container1";
    List<OperatorDeployInfo> c1 = assignContainer(dnm, container1Id, "localhost").getDeployInfo();
    Assert.assertEquals("number operators assigned to container", 1, c1.size());
    Assert.assertTrue(node2.getName() + " assigned to " + container1Id, containsNodeContext(c1, dag.getMeta(node1)));

    for (int i=0; i<TestStaticPartitioningSerDe.partitions.length; i++) {
      String containerId = "container"+(i+1);
      List<OperatorDeployInfo> cc = assignContainer(dnm, containerId, "localhost").getDeployInfo();
      Assert.assertEquals("number operators assigned to container", 1, cc.size());
      Assert.assertTrue(node2.getName() + " assigned to " + containerId, containsNodeContext(cc, dag.getMeta(node2)));

      // n1n2 in, mergeStream out
      OperatorDeployInfo ndi = cc.get(0);
      Assert.assertEquals("type " + ndi, OperatorDeployInfo.OperatorType.GENERIC, ndi.type);
      Assert.assertEquals("inputs " + ndi, 1, ndi.inputs.size());
      Assert.assertEquals("outputs " + ndi, 1, ndi.outputs.size());

      InputDeployInfo nidi = ndi.inputs.get(0);
      Assert.assertEquals("stream " + nidi, n1n2.getId(), nidi.declaredStreamId);
      Assert.assertEquals("partition for " + containerId, Sets.newHashSet(PartitioningTestOperator.PARTITION_KEYS[i]), nidi.partitionKeys);
      Assert.assertEquals("serde " + nidi, null, nidi.serDeClassName);
    }

    // unifier
    String mergeContainerId = "mergeContainer";
    List<OperatorDeployInfo> cUnifier = assignContainer(dnm, mergeContainerId, "localhost").getDeployInfo();
    Assert.assertEquals("number operators assigned to " + mergeContainerId, 1, cUnifier.size());

    OperatorDeployInfo mergeNodeDI = getNodeDeployInfo(cUnifier,  dag.getMeta(node2));
    Assert.assertNotNull("unifier for " + node2, mergeNodeDI);
    Assert.assertEquals("type " + mergeNodeDI, OperatorDeployInfo.OperatorType.UNIFIER, mergeNodeDI.type);
    Assert.assertEquals("inputs " + mergeNodeDI, 3, mergeNodeDI.inputs.size());
    List<Integer> sourceNodeIds = Lists.newArrayList();
    for (InputDeployInfo nidi : mergeNodeDI.inputs) {
      Assert.assertEquals("streamName " + nidi, n2n3.getId(), nidi.declaredStreamId);
      String mergePortName = "<merge#" +  dag.getMeta(node2).getMeta(node2.outport1).getPortName() + ">";
      Assert.assertEquals("portName " + nidi, mergePortName, nidi.portName);
      Assert.assertNotNull("sourceNodeId " + nidi, nidi.sourceNodeId);
      Assert.assertNotNull("contextAttributes " + nidi, nidi.contextAttributes);
      Assert.assertEquals("contextAttributes " , new Integer(1111), nidi.attrValue(PortContext.QUEUE_CAPACITY, 0));
      sourceNodeIds.add(nidi.sourceNodeId);
    }
    for (PTOperator node : dnm.getPhysicalPlan().getOperators(dag.getMeta(node2))) {
      Assert.assertTrue(sourceNodeIds + " contains " + node.getId(), sourceNodeIds.contains(node.getId()));
    }

    Assert.assertEquals("outputs " + mergeNodeDI, 1, mergeNodeDI.outputs.size());
    for (OutputDeployInfo odi : mergeNodeDI.outputs) {
      Assert.assertNotNull("contextAttributes " + odi, odi.contextAttributes);
      Assert.assertEquals("contextAttributes " , new Integer(2222), odi.attrValue(PortContext.QUEUE_CAPACITY, 0));
    }

    try {
      InputStream stream = new HdfsStorageAgent(new Configuration(false), dag.getAttributes().attr(DAGContext.APPLICATION_PATH).get() + "/" + DAGContext.SUBDIR_CHECKPOINTS).getLoadStream(mergeNodeDI.id, -1);
      Operator operator = Node.retrieveNode(stream, OperatorType.UNIFIER).getOperator();
      stream.close();
      Assert.assertTrue("" + operator,  operator instanceof DefaultUnifier);
    }
    catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    // node3 container
    String node3ContainerId = "node3Container";
    List<OperatorDeployInfo> cmerge = assignContainer(dnm, node3ContainerId, "localhost").getDeployInfo();
    Assert.assertEquals("number operators assigned to " + node3ContainerId, 1, cmerge.size());

    OperatorDeployInfo node3DI = getNodeDeployInfo(cmerge,  dag.getMeta(node3));
    Assert.assertNotNull(node3.getName() + " assigned", node3DI);
    Assert.assertEquals("inputs " + node3DI, 1, node3DI.inputs.size());
    InputDeployInfo node3In = node3DI.inputs.get(0);
    Assert.assertEquals("streamName " + node3In, n2n3.getId(), node3In.declaredStreamId);
    Assert.assertEquals("portName " + node3In, dag.getMeta(node3).getMeta(node3.inport1).getPortName(), node3In.portName);
    Assert.assertNotNull("sourceNodeId " + node3DI, node3In.sourceNodeId);
    Assert.assertEquals("sourcePortName " + node3DI, mergeNodeDI.outputs.get(0).portName, node3In.sourcePortName);

  }

  /**
   * Verify buffer server address when downstream node is assigned before upstream.
   */
  @Test
  public void testBufferServerAssignment() {
    LogicalPlan dag = new LogicalPlan();

    GenericTestOperator node1 = dag.addOperator("node1", GenericTestOperator.class);
    GenericTestOperator node2 = dag.addOperator("node2", GenericTestOperator.class);
    GenericTestOperator node3 = dag.addOperator("node3", GenericTestOperator.class);

    dag.addStream("n1n2", node1.outport1, node2.inport1);

    dag.addStream("n2n3", node2.outport1, node3.inport1);

    dag.getAttributes().attr(LogicalPlan.CONTAINERS_MAX_COUNT).set(2);

    // node1 and node3 are assigned, node2 unassigned
    StreamingContainerManager dnmgr = new StreamingContainerManager(dag);
    assignContainer(dnmgr, "container1", "localhost");
  }

  @Test
  public void testRecoveryOrder() throws Exception
  {
    LogicalPlan dag = new LogicalPlan();

    GenericTestOperator node1 = dag.addOperator("node1", GenericTestOperator.class);
    GenericTestOperator node2 = dag.addOperator("node2", GenericTestOperator.class);
    GenericTestOperator node3 = dag.addOperator("node3", GenericTestOperator.class);

    dag.addStream("n1n2", node1.outport1, node2.inport1);
    dag.addStream("n2n3", node2.outport1, node3.inport1);

    dag.getAttributes().attr(LogicalPlan.CONTAINERS_MAX_COUNT).set(2);

    StreamingContainerManager scm = new StreamingContainerManager(dag);
    Assert.assertEquals(""+scm.containerStartRequests, 2, scm.containerStartRequests.size());
    scm.containerStartRequests.clear();

    PhysicalPlan plan = scm.getPhysicalPlan();

    List<PTContainer> containers = plan.getContainers();
    Assert.assertEquals(""+containers, 2, plan.getContainers().size());

    PTContainer c1 = containers.get(0);
    Assert.assertEquals("c1.operators "+c1.getOperators(), 2, c1.getOperators().size());

    PTContainer c2 = containers.get(1);
    Assert.assertEquals("c2.operators "+c2.getOperators(), 1, c2.getOperators().size());

    String c1Id = "container1";
    String c2Id = "container2";

    assignContainer(scm, c1Id, "localhost");
    Assert.assertEquals(""+c1.getOperators(), c1Id, c1.getExternalId());
    StramChildAgent sca1 = scm.getContainerAgent(c1.getExternalId());

    assignContainer(scm, c2Id, "localhost");
    Assert.assertEquals(""+c1.getOperators(), c1Id, c1.getExternalId());
    StramChildAgent sca2 = scm.getContainerAgent(c2.getExternalId());
    Assert.assertEquals("", 0, sca1.container.getPendingUndeploy().size());
    Assert.assertEquals("", 2, sca1.container.getPendingDeploy().size());

    scm.scheduleContainerRestart(c1.getExternalId());
    Assert.assertEquals("", 0, sca1.container.getPendingUndeploy().size());
    Assert.assertEquals("", 2, sca1.container.getPendingDeploy().size());
    Assert.assertEquals(""+scm.containerStartRequests, 1, scm.containerStartRequests.size());
    ContainerStartRequest dr = scm.containerStartRequests.peek();
    Assert.assertNotNull(dr);

    Assert.assertEquals(""+sca2.container.getPendingUndeploy(), 1, sca2.container.getPendingUndeploy().size());
    Assert.assertEquals(""+sca2.container.getPendingDeploy(), 1, sca2.container.getPendingDeploy().size());

  }


  public static class TestStaticPartitioningSerDe extends DefaultStatefulStreamCodec<Object> {

    public final static int[] partitions = new int[]{
      0, 1, 2
    };

    @Override
    public int getPartition(Object o)
    {
      if (o instanceof Tuple) {
        throw new UnsupportedOperationException("should not be called with control tuple");
      }
      return partitions[0];
    }

  }

  private boolean containsNodeContext(List<OperatorDeployInfo> di, OperatorMeta nodeConf) {
    return getNodeDeployInfo(di, nodeConf) != null;
  }

  private static OperatorDeployInfo getNodeDeployInfo(List<OperatorDeployInfo> di, OperatorMeta nodeConf) {
    for (OperatorDeployInfo ndi : di) {
      if (nodeConf.getName().equals(ndi.declaredId)) {
        return ndi;
      }
    }
    return null;
  }

  private static InputDeployInfo getInputDeployInfo(OperatorDeployInfo ndi, String streamId) {
    for (InputDeployInfo in : ndi.inputs) {
      if (streamId.equals(in.declaredStreamId)) {
        return in;
      }
    }
    return null;
  }

  private static StramChildAgent assignContainer(StreamingContainerManager scm, String containerId, String host) {
    return scm.assignContainer(new ContainerResource(0, containerId, host, 1024), InetSocketAddress.createUnresolved(containerId+"Host", 0));
  }

}
