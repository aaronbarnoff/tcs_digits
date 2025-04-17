import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class DimacsFileGenerator {

	enum SBStrategy {
		WITHOUT_SB, BFS_SB, DFS_SB, CLIQUE_SB
	}

	public SBStrategy getSBStrategyByNum(int num) {
		switch (num) {
			case 0:
				return SBStrategy.WITHOUT_SB;
			case 1:
				return SBStrategy.BFS_SB;
			case 2:
				return SBStrategy.DFS_SB;
			case 3:
				return SBStrategy.CLIQUE_SB;
			default:
				return SBStrategy.BFS_SB;
		}
	}
	private APTA apta;
	private ConsistencyGraph cg;
	private int colors;
	private int maxVar;
	private int vertices;
	private Set<String> alphabet;
	private int[][] x;
	private Map<String, Integer>[][] y;
	private Map<String, Integer>[] u;
	private int[] z;
	private int[][] e;
	private Map<String, Integer>[][] m;
	private int[][] p;
	private List<List<Integer>> n;
	private List<List<Integer>> o;
	private List<Integer> f;
	private String tmpFile = "tmp";
	private String dimacsFile;
	private SBStrategy SB;
	private int noisyP;
	private int noisySize;
	private Set<Integer> acceptableClique;
	private Set<Integer> rejectableClique;
	private int color = 0;
	private List<Integer> ends;
	private boolean fixMode;
	private int exhaustOrder = 0;

	private String[][] baseDFATrans;
	private int numOutputs;
	private String pd;
	int[] pdArr;
	int pdSz;
	int[][][] cf;
	int[][] ost;
	int ostSz;
	int maxVal;

	public DimacsFileGenerator(APTA apta, ConsistencyGraph cg, int colors, int SB, int noisyP, String dimacsFile, boolean fixMode, int numOutputs, String pd, String[][] baseDFATrans, int ostSz) throws IOException {
		init(apta, cg, colors, getSBStrategyByNum(SB), noisyP, dimacsFile, fixMode, numOutputs, pd, baseDFATrans, ostSz);
	}
	@SuppressWarnings("unchecked")
	private void init(APTA apta, ConsistencyGraph cg, int colors, SBStrategy SB, int noisyP, String dimacsFile, boolean fixMode, int numOutputs, String pd, String[][] baseDFATrans, int ostSz) throws IOException {

		this.apta = apta;
		this.cg = cg;
		this.colors = colors;
		this.maxVar = 1;
		this.vertices = apta.getSize();
		this.dimacsFile = dimacsFile;
		this.alphabet = apta.getAlphabet();
		this.ends = new ArrayList<>();
		this.fixMode = fixMode; //unused
		this.SB = SB;			//unused
		this.noisyP = noisyP;   //unused

		this.pd = pd;
		this.pdArr = Arrays.stream(pd.split(" ")).mapToInt(Integer::parseInt).toArray();	//period of continued fraction
		this.pdSz = pdArr.length;
		this.cf = new int[colors][pdSz][2]; //+1 for blank

		this.x = new int[vertices][colors];
		this.y = new HashMap[colors][colors];
		this.z = new int[colors];

		this.baseDFATrans = baseDFATrans;
		this.ostSz = ostSz; //number of types (states) in ost base
		this.ost = new int[colors][ostSz];

		this.maxVal = Arrays.stream(pdArr).max().orElse(0);

		//CE Parent relation variables - y[i][j][a]
		for (int i = 0; i < colors; i++) {
			for (int j = 0; j < colors; j++) {
				y[i][j] = new HashMap<>();
				for (String label : alphabet) {
					y[i][j].put(label, newVariable()); // 081: dict588 1089 transition variables
					exhaustOrder++;
				}
			}
		}

		//CE color variables - z[i]
		for (int v = 0; v < vertices; v++) {
			for (int i = 0; i < colors; i++) {
				x[v][i] = newVariable();
				exhaustOrder++;
			}
		}
		for (int i = 0; i < colors; i++) {
			z[i] = newVariable();
		}

		//find all solutions - fix unused transitions into self loops
		if (fixMode) {
			this.u = new HashMap[colors];
			for (int i = 0; i < colors; i++) {
				u[i] = new HashMap<>();
				for (String label : alphabet) {
					u[i].put(label, newVariable());
				}
			}
		}

		//BFS transition variables: e[i][j]\n");
		this.e = new int[colors][colors];
		for (int i = 0; i < colors; i++) {
			for (int j = i + 1; j < colors; j++) {
				e[i][j] = newVariable();
			}
		}

		//BFS parent variables: p[i][j]\n");
		this.p = new int[colors][colors];
		for (int i = 1; i < colors; i++) {
			for (int j = 0; j < i; j++) {
				p[i][j] = newVariable();
			}
		}

		//BFS symbol variables m[i][j][l]\n");
		this.m = new HashMap[colors][colors];
		for (int i = 0; i < colors; i++) {
			for (int j = i + 1; j < colors; j++) {
				m[i][j] = new HashMap<>();
				for (String label : alphabet) {
					m[i][j].put(label, newVariable());
				}
			}
		}

		for (int state = 0; state < colors; state++){
			for (int type = 0; type < ostSz; type++){
				ost[state][type] = newVariable();
			}
		}

		if (SB == SBStrategy.CLIQUE_SB) {
			acceptableClique = cg.getAcceptableClique();
			rejectableClique = cg.getRejectableClique();
		}

	}

	//constraints for numeration system
	private void NumerationSystemConstraint(Buffer buffer) {
		HashSet<Integer> alph = alphabet.stream().map(Integer::parseInt).collect(Collectors.toCollection(HashSet::new));
		HashSet<Integer>[][] invalidTrans = new HashSet[ostSz][ostSz];
		for (int i = 0; i < ostSz; i++){
			for (int j = 0; j < ostSz; j++) {
				invalidTrans[i][j] = new HashSet<>(alph); 										//start out with all transitions invalid
				if (baseDFATrans[i][j] == null)
					continue;
				StringTokenizer tokenizer = new StringTokenizer(baseDFATrans[i][j], " ");
				while (tokenizer.hasMoreElements()) {
					invalidTrans[i][j].remove(Integer.valueOf(tokenizer.nextToken()));			//remove valid transitions from this set
				}
			}
		}

		//start state self loop on 0
		buffer.addClause(y[0][0].getOrDefault(String.valueOf(0), 0));

		int maxLabel = Integer.valueOf(Collections.max(alphabet));
		//System.out.println(Collections.max(alphabet));

		//Each state can be at least one type
		for (int i = 0; i < colors; i++) {
			StringBuilder sb = new StringBuilder();
			for (int t = 0; t < ostSz; t++) {
				sb.append(ost[i][t]).append(" ");
			}
			buffer.addClause(sb);
		}

		//Each state can be at most one type
		for (int i = 0; i < colors; i++) {
			for (int t = 0; t < ostSz; t++) {
				for (int r = 0; r < ostSz; r++) {
					if (r == t)
						continue;
					buffer.addClause(-ost[i][t], -ost[i][r]);
				}
			}
		}

		//start state in DFAO is always ostDFA base state 0
		buffer.addClause(ost[0][0]);

		//constraints for all base states
		for (int i = 0; i < colors; i++) {
			for (int j = 0; j < colors; j++) {
				for (int s = 0; s < ostSz; s++) {
					for (int t = 0; t < ostSz; t++) {
						for (int m = 0; m <= maxLabel; m++) {
							if (invalidTrans[s][t].contains(m)) {
								buffer.addClause(-ost[i][s], -ost[j][t], -y[i][j].get(String.valueOf(m)));
								//System.out.println(String.format("Adding: " + "-ost[%d][%d], -ost[%d][%d], -y[%d][%d][%d]", i, s, j, t, i, j, m));
							}
						}
					}
				}
			}
		}
	}

	public String generateFile(int amo) throws IOException {
		File tmp = new File(tmpFile);
		try (PrintWriter tmpPW = new PrintWriter(tmp)) {
			Buffer buffer = new Buffer(tmpPW);
			try (PrintWriter pwDF = new PrintWriter(dimacsFile)) {

				//numeration system clauses
				NumerationSystemConstraint(buffer);

				//compact encoding clauses
				printOneAtLeast(buffer);
				printAtMostOneX(buffer, amo);
				printParentRelationIsSet(buffer);
				printParentRelationAtMostOneColor(buffer, amo);
				printParentRelationAtLeastOneColor(buffer); 		//must be modified due to consecutive 1 restriction
				printParentRelationForces(buffer);
				printAccVertDiffColorRej(buffer); 	//contribution from APTA
				printConflictsFromCG(buffer);		//contribution from CG

				if (fixMode && false) {	//required for findall mode, but unusable since we have special constraints
					//printUDefinition(buffer); //required for findall mode on irrelevant state transitions; self-loops cant be used due to no 11 constraint
					//printLoopFix(buffer);
				}

				//SBP constraints
				buffer.addClause(x[0][0]); //DFA start state = APTA start state, fix it to be color 0
				printSBPEdgeExist(buffer);
				printSBPMinimalSymbol(buffer);
				printSBPParentExist(buffer);
				printSBPParentBFS(buffer);
				printSBPOrderInLayerBFS(buffer);
				if (apta.getAlphaSize() == 2) {
					printSBPOrderByChildrenSymbolForSizeTwoBFS(buffer);
				} else {
					printSBPOrderByChildrenSymbolBFS(buffer);
				}

				if (SB == SBStrategy.CLIQUE_SB) {		//find max clique
					printAcceptableCliqueSB(buffer);
					printRejectableCliqueSB(buffer);
				}

				int countClauses = buffer.nClauses();

				pwDF.print("p cnf " + (maxVar - 1) + " " + countClauses + "\n");
				//System.out.println(maxVar -1 + " " + countClauses);
				tmpPW.flush();

				try (BufferedReader in = new BufferedReader(new InputStreamReader(
						new FileInputStream(tmp)))) {

					String aLine;
					while ((aLine = in.readLine()) != null) {
						pwDF.print(aLine + "\n");
					}
				}
			}
		}

		tmp.delete();
		return dimacsFile;
	}

	public void banSolution(Automaton automaton, int[] model) throws IOException {
		List<String> cache = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(dimacsFile))) {
			String line = br.readLine();
			if (line.startsWith("p cnf")) {
				String[] tmp = line.split("\\s+");
				tmp[3] = String.valueOf(Integer.parseInt(tmp[3]) + 1);// + 1) + "\n"; //DIMACS PARSER ERROR
				cache.add(tmp[0] + " " + tmp[1] + " " + tmp[2] + " " + tmp[3]);
			} //else - throw ex
			while ((line = br.readLine()) != null) {
				cache.add(line);
			}
		}
		//System.out.println(cache);
		try (PrintWriter pwDF = new PrintWriter(new BufferedWriter(new FileWriter(dimacsFile)))) {
			for (String s : cache) {
				pwDF.println(s);
			}
			Buffer buffer = new Buffer(pwDF);
			StringBuilder sb = new StringBuilder();
//			for (Node state : automaton.getStates()) {
//				for (Entry<String, Node> e : state.getChildren().entrySet()) {
//					sb.append(-y[state.getNumber()][e.getValue().getNumber()].get(e.getKey())).append(" ");
//				}
//			}
			for (int i = 0; i < colors; i++) {
				for (int j = 0; j < colors; j++) {
					for (String label : alphabet) {
						if (model[y[i][j].get(label) - 1] > 0) {
							sb.append(-y[i][j].get(label)).append(" ");
						}
					}
				}
			}
			buffer.addClause(sb);
		}
	}

	//Compact encoding 1: Each vertex has at least one color: (x_{v,1} or x_{v,2} or ... or x_{v, |C|})
	private void printOneAtLeast(Buffer buffer) {
		for (int v = 0; v < vertices; v++) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < colors; i++) {
				sb.append(x[v][i]).append(" ");
			}
			buffer.addClause(sb);
		}
	}

	//Compact Encoding 2: Accepting vertices cannot have the same color as rejecting vertices: [(!x_{v,i} or z_i) and (!x_{w,i} or !z_i), where v is acc, w is rej]
	private void printAccVertDiffColorRej(Buffer buffer) {
		for (int i = 0; i < colors; i++) {
			for (int k = 1; k < numOutputs; k++) { //
				for (Integer n : apta.getOutNodes(k)) {
					buffer.addClause(-x[n][i], z[i]);
				}
			}
			for (Integer n : apta.getOutNodes(0)) {
				buffer.addClause(-x[n][i], -z[i]);
			}
		}
	}

	//Compact Encoding 3: A parent relation is set when a vertex and its parent are colored: (y_{i,j,a} or !x_{p(v),i} or !x_{v,i})
	private void printParentRelationIsSet(Buffer buffer) {
		for (int v = 0; v < vertices; v++) {
			for (int i = 0; i < colors; i++) {
				for (int j = 0; j < colors; j++) {
					Node cur = apta.getNode(v);
					for (Entry<String, Node> e : cur.getParents().entrySet()) {
						buffer.addClause(y[i][j].get(e.getKey()), -x[e.getValue().getNumber()][i], -x[v][j]);
					}
				}
			}
		}
	}

	//Compact Encoding 4: Each parent relation can target at most one color: [(!y_{i,h,a} or !y_{i,j,a}) where a in Alphabet, h < j]
	private void printParentRelationAtMostOneColor(Buffer buffer, int amo) {
		List<Integer> yList = new ArrayList<>();
		for (String st : apta.getAlphabet()) {
			for (int i = 0; i < colors; i++) {
				yList.clear();
				for (int j = 0; j < colors; j++) {
					yList.add(y[i][j].get(st));
				}
				atMostOne(buffer, yList, amo);
			}
		}
	}

	//Compact Encoding 5: Each vertex has at most one color: [(!x_{v,i} or !x_{v,j}) where i < j]
	private void printAtMostOneX(Buffer buffer, int amo) {
		List<Integer> xList = new ArrayList<>();
		for (int v = 0; v < vertices; v++) {
			xList.clear();
			for (int i : x[v]) {
				xList.add(i);
			}
			atMostOne(buffer, xList, amo);
		}
	}

	//Compact Encoding 6: Each parent relation must target at least one color: (y_{i,1,a} or ... or y_{i,|C|,a})
	//this is modified because of numeration system constraints eg no consecutive 1's
	private void printParentRelationAtLeastOneColor(Buffer buffer) {
		/*
		for (String st : apta.getAlphabet()) {
			for (int i = 0; i < colors; i++) {
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < colors; j++) {
					sb.append(y[i][j].get(st)).append(" ");
				}
				buffer.addClause(sb);
			}
			break; //stop at "0"
		}
		 */
	}

	//Compact Encoding 7: A parent relation forces a vertex once the parent is colored: [(!y_{i,j,l(v)} or !x_{p(v),i} or x_{v,i})]
	private void printParentRelationForces(Buffer buffer) {
		for (int v = 0; v < vertices; v++) {
			for (int i = 0; i < colors; i++) {
				for (int j = 0; j < colors; j++) {
					Node cur = apta.getNode(v);
					for (Entry<String, Node> e : cur.getParents().entrySet()) {
						buffer.addClause(-y[i][j].get(e.getKey()), -x[e.getValue().getNumber()][i], x[v][j]);
					}
				}
			}
		}
	}

	//Compact Encoding 8: All CG determinization conflicts explicitly added as clauses: [(!x_{v,i} or !x_{w,i}) where (v,w) - edge from cg]
	private void printConflictsFromCG(Buffer buffer) {
		int tmpCnt = 0;
		for (int v = 0; v < cg.getSize(); v++) {
			Set<Integer> neighbors = cg.getEdges().get(v);
			for (int w : neighbors){
				if (w > v) {
					continue;
				}
				//System.out.println(v + " " + w);
				for (int i = 0; i < colors; i++) {
					buffer.addClause(-x[w][i], -x[v][i]);
					tmpCnt++;
				}
			}
		}
		//System.out.println(tmpCnt);
	}

	//Findall Mode, fixing unused transitions to be self loops
	private void printUDefinition(Buffer buffer) {
		for (int i = 0; i < colors; i++) {
			for (String label : alphabet) {
				Set<Integer> vl = apta.getVl(label);
				int uli = u[i].get(label);
				StringBuilder tmp = new StringBuilder(-uli + " ");
				for (int vi : vl) {
					buffer.addClause(uli, -x[vi][i]);
					tmp.append(x[vi][i]).append(" ");
				}
				buffer.addClause(tmp);
			}
		}
	}
	private void printLoopFix(Buffer buffer) {
		for (int i = 0; i < colors; i++) {
			for (String label : alphabet) {
				buffer.addClause(u[i].get(label), y[i][i].get(label));
			}
		}
	}

	// BFS 1: transition definition: e_{i,j} <=> y_{i,j,k_1} or ... or y_{i,j,k_n}\n")
	private void printSBPEdgeExist(Buffer buffer) {
		for (int i = 0; i < colors; i++) {
			for (int j = i + 1; j < colors; j++) {
				int eij = e[i][j];
				StringBuilder tmp = new StringBuilder(-eij + " ");
				for (String label : alphabet) {
					buffer.addClause(eij, -y[i][j].get(label));
					tmp.append(y[i][j].get(label)).append(" ");
				}
				buffer.addClause(tmp);
			}
		}
	}

	// BFS 2:  parent definition: [p_{i,j} <=> e_{j,i} and !e{j-1,i} and ... and !e{0, i}]
	private void printSBPParentBFS(Buffer buffer) {
		for (int i = 1; i < colors; i++) {
			for (int j = 0; j < i; j++) {
				StringBuilder tmp = new StringBuilder(p[i][j] + " " + -e[j][i] + " ");
				buffer.addClause(-p[i][j], e[j][i]);

				for (int k = 0; k < j; k++) {
					buffer.addClause(-p[i][j], -e[k][i]);
					tmp.append(e[k][i]).append(" ");
				}
				buffer.addClause(tmp);
			}
		}
	}

	//BFS 3: each state has a parent of smaller number: p_{i,1} or ... or p_{i,i-1}]
	private void printSBPParentExist(Buffer buffer) {
		for (int i = 1; i < colors; i++) {
			StringBuilder tmp = new StringBuilder();
			for (int j = 0; j < i; j++) {
				tmp.append(p[i][j]).append(" ");
			}
			buffer.addClause(tmp);
		}
	}

	//BFS 4:  layer order for children of different parents: [p_{i,j} => !p_{i+1,j-q}]
	private void printSBPOrderInLayerBFS(Buffer buffer) {
		for (int i = 1; i < colors - 1; i++) {
			for (int j = 0; j < i; j++) {
				for (int k = 0; k < j; k++) {
					buffer.addClause(-p[i][j], -p[i + 1][k]);
				}
			}
		}
	}

	//BFS 5: layer order for children of one parent: [p_{i,j} and p_{i+1,j} => y_{j,i,0} and y_{j,i+1,1}]
	private void printSBPOrderByChildrenSymbolForSizeTwoBFS(Buffer buffer) {
		for (int i = 1; i < colors - 1; i++) {
			for (int j = 0; j < i; j++) {
				buffer.addClause(-p[i][j], -p[i + 1][j], y[j][i].get("0"));
				buffer.addClause(-p[i][j], -p[i + 1][j], y[j][i + 1].get("1"));
			}
		}
	}


	// BFS 6: SBP BFS[6] minimal symbol: m_{i,j,c_k} <=> e_{i,j} and y_{i,j,c_k} and !y_{i,j,c_(k-1)} and ... and !y_{i,j,c_1}
	private void printSBPMinimalSymbol(Buffer buffer) {
		for (int i = 0; i < colors; i++) {
			for (int j = i + 1; j < colors; j++) {
				for (String label : alphabet) {
					int curM = m[i][j].get(label);

					buffer.addClause(-curM, e[i][j]);
					buffer.addClause(-curM, y[i][j].get(label));

					StringBuilder tmp = new StringBuilder(curM + " " + -e[i][j]	+ " " + -y[i][j].get(label) + " ");
					for (String prevLabel : alphabet) {
						if (prevLabel.equals(label)) {
							break;
						}
						buffer.addClause(-curM, -y[i][j].get(prevLabel));
						tmp.append(y[i][j].get(prevLabel)).append(" ");
					}
					buffer.addClause(tmp);
				}
			}
		}
	}

	//BFS 7: Order Children by Symbols (alphabet > 2): [p_{i,j} and p_{i+1,j} and m_{j,i,c_k} => !m_{j,i+1,c_(k-q)}]
	private void printSBPOrderByChildrenSymbolBFS(Buffer buffer) {
		for (int i = 1; i < colors - 1; i++) {
			for (int j = 0; j < i; j++) {
				for (String label : alphabet) {
					for (String prevLabel : alphabet) {
						if (label.equals(prevLabel)) {
							break;
						}
						buffer.addClause(-p[i][j], -p[i + 1][j], -m[j][i].get(label), -m[j][i + 1].get(prevLabel));
					}
				}
			}
		}
	}

	//unused
	private void printAcceptableCliqueSB(Buffer buffer) {
		for (int i : acceptableClique) {
			if (color < colors) {
				buffer.addClause(x[i][color]);
				buffer.addClause(z[color]);
				color++;
			} else {
				break;
			}
		}
	}

	private void printRejectableCliqueSB(Buffer buffer) {
		for (int i : rejectableClique) {
			if (color < colors) {
				buffer.addClause(x[i][color]);
				buffer.addClause(-z[color]);
				color++;
			} else {
				break;
			}
		}
	}

	private void atMostOne(Buffer buffer, List<Integer> vars, int amo) {
		switch (amo) {
			case 1:
				atMostOnePairwise(buffer, vars);
				break;
			case 2:
				atMostOneBinary(buffer, vars);
				break;
			case 3:
				atMostOneCommander(buffer, vars, (int) Math.ceil(Math.sqrt((double) colors)));
				break;
			case 4:
				atMostOneCommander(buffer, vars, (colors + 1) / 2);
				break;
			case 5:
				atMostOneProduct(buffer, vars);
				break;
			case 6:
				atMostOneSequential(buffer, vars);
				break;
			case 7:
				atMostOneBimander(buffer, vars, (int) Math.ceil(Math.sqrt((double) colors)));
				break;
			case 8:
				atMostOneBimander(buffer, vars, (colors + 1) / 2);
				break;
		}
	}

	private void atMostOnePairwise(Buffer buffer, List<Integer> vars) {
		int n = vars.size();
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				buffer.addClause(-vars.get(i), -vars.get(j));
			}
		}
	}

	private void atMostOneBinary(Buffer buffer, List<Integer> vars) {
		int n = vars.size();
		int k = log2(n);
		int[] b = new int[k];
		for (int i = 0; i < k; i++) {
			b[i] = newVariable();
		}
		BitMask bm = new BitMask(k);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < k; j++) {
				int sign = bm.get(j) ? 1 : -1;
				buffer.addClause(-vars.get(i), sign * b[j]);
			}
			bm.next();
		}
	}

	private void atMostOneCommander(Buffer buffer, List<Integer> vars, int m) {
		int n = vars.size();
		int g = (int) Math.ceil((double) n / m);
		int[] c = new int[m];
		for (int i = 0; i < m; i++) {
			c[i] = newVariable();
		}
		int curGfrom = 0;
		int curGto = g;
		int j = 0;
		while (curGfrom != n) {
			StringBuilder alo = new StringBuilder();
			List<Integer> amo = new ArrayList<>();
			for (int i = curGfrom; i < curGto; i++) {
				alo.append(vars.get(i)).append(" ");
				amo.add(vars.get(i));
			}
			alo.append(-c[j]);
			amo.add(-c[j]);
			buffer.addClause(alo);
			atMostOnePairwise(buffer, amo);
			curGfrom = curGto;
			curGto = Math.min(curGto + g, n);
			j++;
		}
	}

	private void atMostOneProduct(Buffer buffer, List<Integer> vars) {
		int n = vars.size();
		int uSize = (int) Math.sqrt(n);
		int vSize = uSize;
		while (uSize * vSize < n) {
			vSize++;
		}
		List<Integer> u = new ArrayList<>();
		List<Integer> v = new ArrayList<>();

		for (int i = 0; i < uSize; i++) {
			u.add(newVariable());
		}
		for (int i = 0; i < vSize; i++) {
			v.add(newVariable());
		}

		atMostOnePairwise(buffer, u);
		atMostOnePairwise(buffer, v);
		for (int i = 0; i < uSize; i++) {
			for (int j = 0; j < vSize; j++) {
				if (j * uSize + i > n - 1) {
					break;
				}
				buffer.addClause(-vars.get(j * uSize + i), u.get(i));
				buffer.addClause(-vars.get(j * uSize + i), v.get(j));
			}
		}
	}

	private void atMostOneSequential(Buffer buffer, List<Integer> vars) {
		int n = vars.size();
		int[] s = new int[n - 1];
		for (int i = 0; i < s.length; i++) {
			s[i] = newVariable();
		}
		buffer.addClause(-vars.get(0), s[0]);
		buffer.addClause(-vars.get(n - 1), -s[n - 2]);
		for (int i = 1; i < n - 1; i++) {
			buffer.addClause(-vars.get(i), s[i]);
			buffer.addClause(-s[i - 1], s[i]);
			buffer.addClause(-vars.get(i), -s[i - 1]);
		}
	}

	private void atMostOneBimander(Buffer buffer, List<Integer> vars, int m) {
		int n = vars.size();
		int g = (int) Math.ceil((double) n / m);
		int[] b;
		int k = log2(m);
		BitMask bm = new BitMask(k);
		int curGfrom = 0;
		int curGto = g;
		//first part of bimander. AMO in groups
		while (curGfrom != n) {
			for (int i = curGfrom; i < curGto - 1; i++) {
				for (int j = i + 1; j < curGto; j++) {
					buffer.addClause(-vars.get(i), -vars.get(j));
				}
			}
			curGfrom = curGto;
			curGto = Math.min(curGto + g, n);
		}
		//redundant vars
		b = new int[k];
		for (int i = 0; i < k; i++) {
			b[i] = newVariable();
		}
		curGfrom = 0;
		curGto = g;
		//second part of bimander
		while (curGfrom != n) {
			for (int i = curGfrom; i < curGto; i++) {
				for (int j = 0; j < k; j++) {
					int sign = bm.get(j) ? 1 : -1;
					buffer.addClause(-vars.get(i), sign * b[j]);
				}
			}
			curGfrom = curGto;
			curGto = Math.min(curGto + g, n);
			bm.next();
		}
	}

	public int[][] getX() {
		return x;
	}

	public Map<Integer, Integer> getF() {
		if (noisyP > 0) {
			Map<Integer, Integer> ff = new HashMap<>();
			for (int i = 0; i < ends.size(); i++) {
				ff.put(ends.get(i), f.get(i));
			}
			return ff;
		} else {
			throw new NullPointerException("F is undefined in the noiseless mode.");
		}
	}

	private static int log2(int n) {
		if (n <= 0) {
			throw new IllegalArgumentException();
		}
		return 31 - Integer.numberOfLeadingZeros(n);
	}

	private class BitMask {
		boolean[] ar;
		int n;

		BitMask(int n) {
			this.n = n;
			ar = new boolean[n];
		}

		void next() {
			for (int i = 0; i < n; i++) {
				if (ar[i]) {
					ar[i] = false;
				} else {
					ar[i] = true;
					break;
				}
			}
		}

		boolean get(int i) {
			return ar[i];
		}
	}

	int[] getY(){
		int[] res = new int[y.length*y[0].length*alphabet.size()];
		int k = 0;
		for (int i = 0; i < colors; i++) {
			for (int j = 0; j < colors; j++) {
				for (String label : alphabet) {
					res[k] = y[i][j].get(label);
					k++;
					//System.out.println(String.format("y[%d][%d][%s]=%d",i,j,label,y[i][j].get(label)));
				}
			}
		}
		return res;
	}

	int getExhaustOrder() {return exhaustOrder;}

	int getMaxVar(){
		return maxVar;
	}

	private int newVariable() {
		return maxVar++;
	}
}
