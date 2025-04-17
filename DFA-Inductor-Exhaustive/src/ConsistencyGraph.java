import java.util.*;
import java.util.Map.Entry;

public class ConsistencyGraph {
	//private Map<Integer, Set<Integer>> edges;
	private APTA apta;
	Map<Integer, Triple> merged = new HashMap<>();
	Map<Integer, Triple> mergedInit = new HashMap<>();
	private Set<Integer> acceptableClique;
	private Set<Integer> rejectableClique;
	private int size;
	private List<Set<Integer>> edges;
	private List<Set<Integer>> outputSet;
	private int numOutputs;

	public ConsistencyGraph(APTA apta, boolean noisyMode, boolean is_empty, int numOutputs) {
		this.edges = new ArrayList<>();
		this.apta = apta;
		this.size = apta.getSize();

		for (int i = 0; i < size; i++) {
			edges.add(new HashSet<>());
		}

		/*
		outputSet = new ArrayList<Set<Integer>>();
		for (int i = 0; i < numOutputs; i++) {
			outputSet.add(apta.getOutNodes(i));
		}
		for (int k = 0; k < numOutputs; k++) {
			for (int l = k + 1; l < numOutputs; l++) {
				for (int i : outputSet.get(k)) {
					for (int j : outputSet.get(l)) {
						edges.get(i).add(j);
						edges.get(j).add(i);
					}
				}
			}
		}
		 */
		// Skipped determinization, found to be too slow.
		if (!is_empty) {
			for (int node_id = 1; node_id < apta.getSize(); node_id++) {
				for (int other_id = 0; other_id < node_id; other_id++) {
					if (!_tryToMerge(apta.getNode(node_id), apta.getNode(other_id), new HashMap<>())) {
						edges.get(node_id).add(other_id);
					}
				}
			}
		}
	}

	private boolean _hasEdge(int id1, int id2) {
		return edges.get(id1).contains(id2) || edges.get(id2).contains(id1);
	}

	public int getSize() {
		return size;
	}

	public List<Set<Integer>> getEdges() {
		return edges;
	}

	// This algorithm was adapted from the Python DFA-Inductor version (https://github.com/ctlab/DFA-Inductor-py) as it seemed faster.
	// Source: https://github.com/ctlab/DFA-Inductor-py/blob/master/dfainductor/structures.py
	// However, still didn't use it as it generally took longer to create a full CG than to solve without it
	private boolean _tryToMerge(Node node, Node other, Map<Integer, Tuple<Integer, Integer>> reps) {
		Tuple<Integer, Integer> nodeRep = reps.getOrDefault(node.getNumber(), new Tuple<>(node.getNumber(), node.getStatus()));
		Tuple<Integer, Integer> otherRep = new Tuple<>(other.getNumber(), other.getStatus());
		//int nodeRepStatus = node.getStatus();
		//int statusNode2 = other.getStatus();

		if ((!nodeRep.second().equals(otherRep.second()) && (!nodeRep.second().equals(-1) && !otherRep.second.equals(-1)))) {
			//System.out.println(node.getNumber() + " and " + other.getNumber() + " conflict");
			return false;
		}
		return true;
		/*
		else
		{
			if (nodeRep.first() < otherRep.first()) {
				reps.put(otherRep.first(), new Tuple<>(nodeRep.first(), Math.min(nodeRep.second(), otherRep.second())));
			} else {
				reps.put(nodeRep.first(), new Tuple<>(otherRep.first(), Math.min(nodeRep.second(), otherRep.second())));
			}
			for (Entry<String, Node> entry : node.getChildren().entrySet()) {
				String label = String.valueOf(entry.getKey());
				Node child = entry.getValue();
				Node otherChild = other.getChild(String.valueOf(label));

				if (otherChild != null) {
					//System.out.println("testing children of " + node.getNumber() + " vs "+ other.getNumber() + ": " + child.getNumber() + " vs " + otherChild.getNumber() );
					if (!_tryToMerge(child, otherChild, reps)) {
						//System.out.println(node.getNumber() + " and " + other.getNumber() + " conflict via child " + child.getNumber() + " vs " + otherChild.getNumber());
						return false;
					}
				}
			}
		}
		//System.out.println(node.getNumber() + ", " + other.getNumber() + " no con");
		return true;
		 */
	}

	private static class Tuple<K, V> {
		private final K first;
		private final V second;

		public Tuple(K first, V second) {
			this.first = first;
			this.second = second;
		}

		public K first() {
			return first;
		}

		public V second() {
			return second;
		}
	}

	/*
	public void update(int new_nodes_from) {
		for (int node_id = new_nodes_from; node_id < size; node_id++) {
			edges.add(new HashSet<>());
			for (int other_id = 0; other_id < node_id; other_id++) {
				if (!_tryToMerge(apta.getNode(node_id), apta.getNode(other_id), reps)){//, new HashMap<>())) {
					edges.get(node_id).add(other_id);
				}
			}
		}
	}
	*/

	private class Triple {
		int num;
		boolean isAcc;
		boolean isRej;

		public Triple(int num, boolean isAcc, boolean isRej) {
			this.num = num;
			this.isAcc = isAcc;
			this.isRej = isRej;
		}
	}

	public void findClique() {
		int maxDegree = 0;
		int maxV = -1;
		acceptableClique = new HashSet<>();
		for (int candidate : apta.getAcceptableNodes()) {
			int candidateDegree = getEdges().get(candidate).size();
			if (candidateDegree > maxDegree) {
				maxDegree = candidateDegree;
				maxV = candidate;
			}
		}
		int last = maxV;
		if (last != -1) {
			acceptableClique.add(last);
			int anotherOne = findNeighbourWithHighestDegree(
					acceptableClique, last, true);
			while (anotherOne != -1) {
				acceptableClique.add(anotherOne);
				last = anotherOne;
				anotherOne = findNeighbourWithHighestDegree(
						acceptableClique, last, true);
				if (last == anotherOne) {
					break;
				}
			}
		}

		maxDegree = 0;
		maxV = -1;
		rejectableClique = new HashSet<>();
		for (int candidate : apta.getRejectableNodes()) {
			int candidateDegree = getEdges().get(candidate).size();
			if (candidateDegree > maxDegree) {
				maxDegree = candidateDegree;
				maxV = candidate;
			}
		}
		last = maxV;
		if (last != -1) {
			rejectableClique.add(last);
			int anotherOne = findNeighbourWithHighestDegree(
					rejectableClique, last, false);
			while (anotherOne != -1) {
				rejectableClique.add(anotherOne);
				last = anotherOne;
				anotherOne = findNeighbourWithHighestDegree(
						rejectableClique, last, false);
				if (last == anotherOne) {
					break;
				}
			}
		}
	}

	private int findNeighbourWithHighestDegree(Set<Integer> cur, int v, boolean acceptable) {
		int maxDegree = 0;
		int maxNeighbour = -1;
		// uv - edge
		for (int u : getEdges().get(v)) {
			int cont = 0;
			for (int k = 1; k < numOutputs; k++) {
				if (acceptable && !apta.isOut(k, u)) {
					cont = 1;
					break;
				}
			}
			if (cont == 1)
				continue;
			if (!acceptable && !apta.isOut(0,u)) {
				continue;
			}
			boolean uInClique = true;
			// check if other vertices in cur connected with u
			for (int w : cur) {
				if (w != v) {
					if (!getEdges().get(w).contains(u)) {
						uInClique = false;
						break;
					}
				}
			}
			if (uInClique) {
				int uDegree = getEdges().get(u).size();
				if (uDegree > maxDegree) {
					maxDegree = uDegree;
					maxNeighbour = u;
				}
			}
		}
		return maxNeighbour;
	}

	public Set<Integer> getAcceptableClique() {
		return acceptableClique;
	}

	public Set<Integer> getRejectableClique() {
		return rejectableClique;
	}

	public int getCliqueSize() {
		return acceptableClique.size() + rejectableClique.size();
	}

	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("graph {\n");
		s.append("    layout=\"circo\"\n");
		s.append("    oneblock=true\n");
		s.append("    node [shape = circle];\n");
		s.append("    0 [style = \"bold\"];\n");

		for (int i = 0; i < apta.getSize(); i++) {
			Node state = apta.getNode(i);
			if (state.getStatus() > 0) {
				s.append("    ");
				s.append(state.getNumber());
				s.append(" [peripheries=" + (state.getStatus() + 1) + "]");
				s.append("\n");
			}
		}
		for (int v = 0; v < edges.size(); v++) {
			Set<Integer> neighbors = edges.get(v);
			for (int w : neighbors){
				if (w > v) {
					continue;
				}
				s.append(v);
				s.append(" -- ");
				s.append(w);
				s.append(";\n");
			}
		}
		s.append("}");
		return s.toString();
	}
}
