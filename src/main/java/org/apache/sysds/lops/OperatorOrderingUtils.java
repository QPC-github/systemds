/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.lops;

import org.apache.sysds.common.Types;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OperatorOrderingUtils
{
	// Return a list representation of all the lops in a SB
	public static ArrayList<Lop> getLopList(StatementBlock sb) {
		ArrayList<Lop> lops = null;
		if (sb.getLops() != null && !sb.getLops().isEmpty()) {
			lops = new ArrayList<>();
			for (Lop root : sb.getLops())
				addToLopList(lops, root);
		}
		return lops;
	}

	// Determine if a lop is root of a DAG
	public static boolean isLopRoot(Lop lop) {
		if (lop.getOutputs().isEmpty())
			return true;
		//TODO: Handle internal builtins (e.g. eigen)
		if (lop instanceof FunctionCallCP &&
			((FunctionCallCP) lop).getFnamespace().equalsIgnoreCase(DMLProgram.INTERNAL_NAMESPACE)) {
			return true;
		}
		return false;
	}

	// Gather the Spark operators which return intermediates to local (actions/single_block)
	// In addition count the number of Spark OPs underneath every Operator
	public static int collectSparkRoots(Lop root, Map<Long, Integer> sparkOpCount, List<Lop> sparkRoots) {
		if (sparkOpCount.containsKey(root.getID())) //visited before
			return sparkOpCount.get(root.getID());

		// Aggregate #Spark operators in the child DAGs
		int total = 0;
		for (Lop input : root.getInputs())
			total += collectSparkRoots(input, sparkOpCount, sparkRoots);

		// Check if this node is Spark
		total = root.isExecSpark() ? total + 1 : total;
		sparkOpCount.put(root.getID(), total);

		// Triggering point: Spark action/operator with all CP consumers
		if (isSparkTriggeringOp(root)) {
			sparkRoots.add(root);
			root.setAsynchronous(true); //candidate for async. execution
		}

		return total;
	}

	// Dictionary of Spark operators which are expensive enough to be
	// benefited from persisting if shared among jobs.
	public static boolean isPersistableSparkOp(Lop lop) {
		return lop.isExecSpark() && (lop instanceof MapMult
			|| lop instanceof MMCJ || lop instanceof MMRJ
			|| lop instanceof MMZip);
	}

	private static boolean isSparkTriggeringOp(Lop lop) {
		boolean rightSpLop = lop.isExecSpark() && (lop.getAggType() == AggBinaryOp.SparkAggType.SINGLE_BLOCK
			|| lop.getDataType() == Types.DataType.SCALAR || lop instanceof MapMultChain
			|| lop instanceof PickByCount || lop instanceof MMZip || lop instanceof CentralMoment
			|| lop instanceof CoVariance || lop instanceof MMTSJ || lop.isAllOutputsCP());
		boolean isPrefetched = lop.getOutputs().size() == 1
			&& lop.getOutputs().get(0) instanceof UnaryCP
			&& ((UnaryCP) lop.getOutputs().get(0)).getOpCode().equalsIgnoreCase("prefetch");
		boolean col2Bc = isCollectForBroadcast(lop);
		boolean prefetch = (lop instanceof UnaryCP) &&
			((UnaryCP) lop).getOpCode().equalsIgnoreCase("prefetch");
		return (rightSpLop || col2Bc || prefetch) && !isPrefetched;
	}

	// Determine if the result of this operator is collected to
	// broadcast for the next operator (e.g. mapmm --> map+)
	public static boolean isCollectForBroadcast(Lop lop) {
		boolean isSparkOp = lop.isExecSpark();
		boolean isBc = lop.getOutputs().stream()
			.allMatch(out -> (out.getBroadcastInput() == lop));
		//TODO: Handle Lops with mixed Spark (broadcast) CP consumers
		return isSparkOp && isBc && (lop.getDataType() == Types.DataType.MATRIX);
	}

	private static boolean addNode(ArrayList<Lop> lops, Lop node) {
		if (lops.contains(node))
			return false;
		lops.add(node);
		return true;
	}

	private static void addToLopList(ArrayList<Lop> lops, Lop lop) {
		if (addNode(lops, lop))
			for (Lop in : lop.getInputs())
				addToLopList(lops, in);
	}

}
