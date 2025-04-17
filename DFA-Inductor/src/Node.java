import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Node implements Serializable {

	private int number;
	private Map<String, Node> children;
	private Map<String, Node> parents;
	//private Status status;
	private int status; //-1 = COMMON, output 0 = 0, output 1 = 1, etc.

	public Node(int number) {
		this.number = number;
		this.children = new HashMap<>();
		this.parents = new HashMap<>();
		this.status = -1;
	}

	public Node(int number, int status) {
		this.number = number;
		this.children = new HashMap<>();
		this.parents = new HashMap<>();
		this.status = status;
	}

	public Node(int number, String label, Node parent) {
		this.number = number;
		this.children = new HashMap<>();
		this.parents = new HashMap<>();
		this.parents.put(label, parent);
		parent.children.put(label, this);
		this.status = -1;
	}

	public Node(int number, String label, Node parent, int status) {
		this.number = number;
		this.children = new HashMap<>();
		this.parents = new HashMap<>();
		this.parents.put(label, parent);
		parent.children.put(label, this);
		this.status = status;
	}

	public int getNumber() {
		return number;
	}

	public int getStatus() {
		return status;
	}

	public boolean isOut(int output){ return status == output;}

	public void setStatus(int status) {	this.status = status; }

	public Map<String, Node> getParents() {
		return parents;
	}

	public Node getParent(String s) {
		return parents.containsKey(s) ? parents.get(s) : null;
	}

	public Map<String, Node> getChildren() {
		return children;
	}

	public Node getChild(String s) {
		return children.containsKey(s) ? children.get(s) : null;
	}

	public void addChild(String s, Node child) {
		children.put(s, child);
		child.parents.put(s, this);
	}

	public void addParent(String s, Node parent) {
		parents.put(s, parent);
		parent.children.put(s, this);
	}
}
