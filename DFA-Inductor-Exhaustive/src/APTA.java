import java.io.*;
import java.util.*;

public class APTA implements Serializable {

	private Node root;
	private int size;
	private int words;
	private int alphaSize;
	private Set<String> alphabet;
	private List<Set<Integer>> nodeSet;
	private Map<Integer, Node> indexesOfNodes;
	private Map<String, Set<Integer>> vlset;

	private transient StringTokenizer st = null;

	public APTA(int numOutputs) {
		nodeSet = new ArrayList<Set<Integer>>();
		for (int i = 0; i < numOutputs; i++){
			nodeSet.add(new HashSet<>());
		}
		indexesOfNodes = new HashMap<>();
		vlset = new HashMap<>();
		alphabet = new HashSet<>();
		size = 0;
		root = new Node(size);
		indexesOfNodes.put(size++, root);
	}

	public Set<Integer> getAcceptableNodes() {
		Set<Integer> tmp = new HashSet<Integer>();
		for (int i =1; i < nodeSet.size(); i++) {
			tmp.addAll(nodeSet.get(i));
		}
		return tmp;
	}

	public Set<Integer> getRejectableNodes() {
		return nodeSet.get(0);
	}

	public APTA(InputStream is, int numOutputs, int dictSize) throws IOException {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
			size = 0;

			nodeSet = new ArrayList<Set<Integer>>();
			for (int i = 0; i < numOutputs; i++){
				nodeSet.add(new HashSet<>());
			}
			indexesOfNodes = new HashMap<>();
			vlset = new HashMap<>();
			alphabet = new HashSet<>();

			int lines = nextInt(br);
			lines = dictSize+1; //+1 to account for the first 2 lines "<numLines alphSize>" "0 1 0"
			words = lines;
			int alphaSize = nextInt(br);
			this.alphaSize = alphaSize;
			root = new Node(size);
			indexesOfNodes.put(size++, root);

			Node currentNode;
			Node newNode;
			String label;
			for (int line = 0; line < lines; line++) {
				currentNode = root;
				int status = nextInt(br);
				int len = nextInt(br);
				for (int i = 0; i < len; i++) {
					label = nextToken(br);
					if (!alphabet.contains(label)) {
						alphabet.add(label);
						vlset.put(label, new HashSet<Integer>());
					}
					if (currentNode.getChildren().containsKey(label)) {
						currentNode = currentNode.getChildren().get(label);
					} else {
						vlset.get(label).add(currentNode.getNumber());
						newNode = new Node(size, label, currentNode);
						indexesOfNodes.put(size++, newNode);
						currentNode.addChild(label, newNode);
						currentNode = newNode;
					}
				}
				if (status >=0){
					nodeSet.get(status).add(currentNode.getNumber());
					currentNode.setStatus(status);
				}
				else
				{
					nodeSet.get(0).add(currentNode.getNumber());
					currentNode.setStatus(0);
				}

				//for (int i = 0; i < numOutputs; i++) {
				//	if (status == i) {
				//		nodeSet.get(i).add(currentNode.getNumber());
				//		currentNode.setStatus(i);
				//	}
				//}
				//System.out.println("status "+ status + " label "+ len);
			}
			assert alphabet.size() == alphaSize;
		}
	}

	public boolean isOut(int output, int number){
		return nodeSet.get(output).contains(number);
	}

	public int getSize() {
		return size;
	}

	public int getCountOfWords() {
		return words;
	}

	public int getAlphaSize() {
		return alphaSize;
	}

	public Node getRoot() {
		return root;
	}

	public Set<Integer> getOutNodes(int output){
		return nodeSet.get(output);
	}

	public Set<String> getAlphabet() {
		return alphabet;
	}

	public Set<Integer> getVl(String label) {
		return vlset.get(label);
	}

	// node can be null
	public Node getNode(int i) {
		return indexesOfNodes.get(i);
	}

	private String nextToken(BufferedReader br) throws IOException {
		while (st == null || !st.hasMoreTokens()) {
			String s = br.readLine();
			if (s == null) {
				return "-1";
			}
			st = new StringTokenizer(s);
		}
		return st.nextToken();
	}

	private int nextInt(BufferedReader br) throws IOException {
		return Integer.parseInt(nextToken(br));
	}

	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("digraph Automat {\n");
		s.append("rankdir=\"LR\";\n");
		s.append("    node [shape = circle];\n");
		s.append("    0 [style = \"bold\"];\n");

		for (int i = 0; i < size; i++) {
			Node state = indexesOfNodes.get(i);
			s.append("    ");
			s.append(state.getNumber());
			s.append(" [peripheries="+state.getStatus()+"]\n");
			for (Map.Entry<String, Node> e : state.getChildren().entrySet()) {
				s.append("    ");
				s.append(state.getNumber());
				s.append(" -> ");
				s.append(e.getValue().getNumber());
				s.append(" [label = \"");
				s.append(e.getKey());
				s.append("\"];\n");
			}
		}
		s.append("}");

		return s.toString();
	}

}