import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class AutomatonBuilder implements Serializable {

	public static Automaton build(int[] model, DimacsFileGenerator dfg, APTA apta, int colors, boolean noisyMode, int numOutputs) {
		int vertices = apta.getSize();
		int[][] x = dfg.getX();
		Map<Integer, Integer> f = null;
		if (noisyMode) {
			f = dfg.getF();
		}
		//System.out.println("Colors:"+colors +  "vertices" +vertices);
		Automaton automaton = new Automaton(colors, numOutputs, false);
		Map<Integer, Integer> colorsOfNodes = new HashMap<>();
		//System.out.println(Arrays.toString(model));
		for (int i = 0; i < colors; i++) {
			for (int v = 0; v < vertices; v++) {
				//System.out.println("v: "+v+"i: "+i+ "x[v][i]: "+ x[v][i] +"model:" + model[x[v][i] - 1]);
				if (model[x[v][i] - 1] > 0) {
					colorsOfNodes.put(v, i);
					//System.out.println("Test");
				}
			}
		}

		if (colorsOfNodes.get(0) != 0) {
			int changeColor = colorsOfNodes.get(0);
			for (int v = 0; v < vertices; v++) {
				if (colorsOfNodes.get(v) == changeColor) {
					colorsOfNodes.put(v, 0);
				} else if (colorsOfNodes.get(v) == 0) {
					colorsOfNodes.put(v, changeColor);
				}
			}
		}

		for (Map.Entry<Integer, Integer> e : colorsOfNodes.entrySet()) {
			int vertex = e.getKey();
			int color = e.getValue();
			Node vertexNode = apta.getNode(vertex);
			int curStatus = vertexNode.getStatus();
			if (curStatus >= 0  && !(f != null && model[f.get(vertex) - 1] > 0))
				automaton.getState(color).setStatus(curStatus);
			//for (int i = 0; i < numOutputs; i++) {
			//	if (vertexNode.isOut(i) && !(f != null && model[f.get(vertex) - 1] > 0)) {
			//		automaton.getState(color).setStatus(i);
			//	}
			//}

			for (Map.Entry<String, Node> entry : apta.getNode(vertex).getChildren().entrySet()) {
				String label = entry.getKey();
				int to = entry.getValue().getNumber();
				automaton.addTransition(color, colorsOfNodes.get(to), label);
			}
		}
		return automaton;
	}


}
