import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Automaton implements Serializable{
	private Node start;
	private List<Node> states;
	private int numOutputs;
	private boolean walnutDFA = false;

	public Automaton(Automaton automaton, int numOutputs, boolean walnutDFA) {
		this(automaton.size(), numOutputs, walnutDFA);

		for (int i = 0; i < automaton.getStates().size(); i++) {
			Node thisNode = this.states.get(i);
			Node otherNode = automaton.getStates().get(i);
			thisNode.setStatus(otherNode.getStatus());
			for (Map.Entry<String, Node> entry : otherNode.getChildren().entrySet()) {
				thisNode.addChild(entry.getKey(), this.states.get(entry.getValue().getNumber()));
			}
			for (Map.Entry<String, Node> entry : otherNode.getParents().entrySet()) {
				thisNode.addParent(entry.getKey(), this.states.get(entry.getValue().getNumber()));
			}
		}
	}

	public Automaton(int size, int numOutputs, boolean walnutDFA) {
		int cur = 0;
		this.start = new Node(cur++);
		this.states = new ArrayList<>();
		this.states.add(this.start);
		this.numOutputs = numOutputs;
		this.walnutDFA = walnutDFA;

		while (cur < size) {
			this.states.add(new Node(cur++));
		}
	}

	public Automaton(File file, int numOutputs, boolean walnutDFA) throws IOException {
		this.start = new Node(0);
		this.states = new ArrayList<>();
		this.states.add(this.start);
		this.numOutputs = numOutputs;
		this.walnutDFA = walnutDFA;

		try (BufferedReader automatonBR = new BufferedReader(new FileReader(file))) {
			List<Pattern> patterns = new ArrayList<Pattern>();
			String line;
			Matcher matcher;

			//Walnut pattern for generated .GV regex for validating digits on walnut DFA
			if (walnutDFA) {
				Pattern transitionPattern = Pattern.compile("(\\d+) -> (\\d+)\\[ label = \\\"([a-zA-Z0-9-_]+)\\\"\\];");
				for (int i = 0; i < numOutputs; i++) {
					Pattern tmp = Pattern.compile("node \\[shape = circle, label=\\\"([0-9]+)/" + i + "\\\", fontsize=12\\](\\d+);");
					patterns.add(tmp);
				}
				while ((line = automatonBR.readLine()) != null) {
					if ((matcher = transitionPattern.matcher(line)).matches()) {
						addTransition(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), matcher.group(3));
					}
					for (int i = 0; i < numOutputs; i++) {
						if ((matcher = patterns.get(i).matcher(line)).matches()) {
							getState(Integer.parseInt(matcher.group(2))).setStatus(i);
							break;
						}
					}
				}
			}
			else {
				//Non-walnut pattern:
				Pattern transitionPattern = Pattern.compile("\\s+(\\d+) -> (\\d+)\\s*\\[label = \\\"([a-zA-Z0-9-_]+)\\\"\\];");
				for (int i = 0; i < numOutputs; i++) {
					Pattern tmp = Pattern.compile("\\s+(\\d+)\\s*\\[label = \\\"([0-9]+)/" + i + "\\\"\\]");
					patterns.add(tmp);
				}

				while ((line = automatonBR.readLine()) != null) {
					if ((matcher = transitionPattern.matcher(line)).matches()) {
						addTransition(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), matcher.group(3));
					}
					for (int i = 0; i < numOutputs; i++) {
						if ((matcher = patterns.get(i).matcher(line)).matches()) {
							getState(Integer.parseInt(matcher.group(1))).setStatus(i);
							break;
						}
					}
				}
			}
		}
	}

	public Node getStart() {
		return start;
	}

	public Node getState(int i) {
		return states.get(i);
	}

	public List<Node> getStates() {
		return states;
	}

	public int size() {
		return states.size();
	}

	public void addTransition(int from, int to, String label) {
		if (from >= states.size()) {
			addState(from);
		}
		if (to >= states.size()) {
			addState(to);
		}
		Node fromNode = states.get(from);
		Node toNode = states.get(to);

		fromNode.addChild(label, toNode);
		toNode.addParent(label, fromNode);
	}

	public void addChildren(int num, Map<String, Node> children) {
		Node numNode = states.get(num);
		numNode.getChildren().putAll(children);
	}

	public void addParents(int num, Map<String, Node> children) {
		Node numNode = states.get(num);
		numNode.getParents().putAll(children);
	}

	public int proceedWord(List<String> word, FileWriter myLog) throws IOException {
		Node curNode = start;
		Node childNode;
		for (String label : word) {
			childNode = curNode.getChild(label);
			if (childNode == null)
				childNode = curNode;
			//myLog.append(curNode.getNumber() + " --> " + childNode.getNumber() + " via " + "\"" + label + "\"\n");
			//myLog.flush();
			curNode = childNode;
		}
		//myLog.append("Result: OUTPUT" + curNode.getStatus() + "\n\n");
		return curNode.getStatus();
	}

	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("digraph Automat {\n");
		s.append("	  rankdir=\"LR\";\n");
		s.append("    node [shape = circle];\n");
		s.append("    0 [style = \"bold\"];\n");

		for (Node state : states) {
			int flag = 0;
			for (int i = 0; i < numOutputs; i++) {
				if (state.isOut(i)) {
					s.append("    ");
					s.append(state.getNumber());
					s.append(" [label = \"" + state.getNumber() + "/" + i + "\"]\n");
				}
				else
					flag++;
			}
			if (flag == numOutputs){
				s.append("    ");
				s.append(state.getNumber());
				s.append(" [label = \"" + state.getNumber() + "/" + 0 + "\"]\n"); //unsure how correct this is
			}

			for (Entry<String, Node> e : state.getChildren().entrySet()) {
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

	private String enumerate() {
		Queue<Node> queue = new LinkedList<>();
		boolean[] visited = new boolean[this.size()];
		List<String> alphabet = new ArrayList<>(this.getStart().getChildren().keySet());
		Collections.sort(alphabet);
		int cur_num = 0;

		Map<Integer, Integer> enumeration = new HashMap<>();

		queue.add(this.getStart());
		visited[this.getStart().getNumber()] = true;
		Node cur;
		List<Integer> hash = new ArrayList<>();
		while (!queue.isEmpty()) {
			cur = queue.remove();
			enumeration.put(cur.getNumber(), cur_num++);
			for (String label : alphabet) {
				hash.add(cur.getChild(label).getNumber());
			}
			for (String label : alphabet) {
				Node child = cur.getChild(label);
				if (!visited[child.getNumber()]) {
					visited[child.getNumber()] = true;
					queue.add(child);
				}
			}
		}
		for (int i = 0; i < hash.size(); i++) {
			hash.set(i, enumeration.get(hash.get(i)));
		}
		return hash.toString();
	}

	@Override
	public int hashCode() {
		return enumerate().hashCode();
	}

	@Override
	public boolean equals(Object arg) {
		Automaton obj = (Automaton) arg;
		return this.hashCode() == obj.hashCode();
	}

	private boolean addState(int number) {
		int cur = states.size();
		if (cur <= number) {
			while (cur <= number) {
				this.states.add(new Node(cur++));
			}
			return true;
		}
		return false;
	}
}
