import org.sat4j.reader.ParseFormatException;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.TimeoutException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.exit;
public class Main {

	public static class Config {
		String ostBaseFile;
		String dictFile;
		int    outputBase;
		String cfPeriod;
		int    numStates;
		int    dictNum;
		String dictExhFile;
	}

	private void loadAllConfigs(Path cfgPath) throws IOException {
		try (BufferedReader br = Files.newBufferedReader(cfgPath)) {
			String line;
			String currentKey = null;
			Config cfg = null;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

				if (currentKey == null && line.startsWith("CadicalExhaustPath:")) {
					String val = line.substring(line.indexOf(":") + 1).trim();
					if (val.endsWith(",")) val = val.substring(0, val.length()-1).trim();
					if (val.startsWith("\"") && val.endsWith("\"")) {
						val = val.substring(1, val.length()-1);
					}
					this.cadicalExhaustPath = val;
					continue;
				}

				if (line.endsWith(":")) {
					currentKey = line.substring(0, line.length() - 1).trim();
					cfg = new Config();
					continue;
				}
				if (line.equals("{") || line.equals("}")) {
					if (line.equals("}") && currentKey != null && cfg != null) {
						configs.put(currentKey, cfg);
					}
					continue;
				}
				String[] parts = line.split("=", 2);
				if (parts.length < 2 || cfg == null) continue;
				String key = parts[0].trim();
				String val = parts[1].trim();
				if (val.contains("//")) val = val.substring(0, val.indexOf("//")).trim();
				if (val.endsWith(",")) val = val.substring(0, val.length() - 1).trim();
				if (val.startsWith("\"") && val.endsWith("\"")) {
					val = val.substring(1, val.length() - 1);
				}
				switch (key) {
					case "ostBaseFile":  cfg.ostBaseFile  = val; break;
					case "dictFile":     cfg.dictFile     = val; break;
					case "outputBase":   cfg.outputBase   = Integer.parseInt(val.replaceAll("\\D", "")); break;
					case "cfPeriod":     cfg.cfPeriod     = val; break;
					case "numStates":    cfg.numStates    = Integer.parseInt(val.replaceAll("\\D", "")); break;
					case "dictNum":      cfg.dictNum      = Integer.parseInt(val.replaceAll("\\D", "")); break;
					case "dictExhFile":  cfg.dictExhFile  = val; break;
				}
			}
		}
	}

	private Map<String,Config> configs = new HashMap<>();

	private String OSTBase;
	private String dictFile;
	private int    outputBase;
	private String cfPeriod ;
	private int    numStates;
	private int    dictNum;
	private String dictExhFile;
	private int    colors;
	private String cadicalExhaustPath;

	public static void main(String... args) {
		if (args.length == 2 && args[0].equals("-f")) {
			String code = args[1];
			Main m = new Main();
			try {

				m.loadAllConfigs(Paths.get("config.txt"));
				Config c = m.configs.get(code);
				if (c == null) {
					System.err.println("No config for code: " + code);
					System.exit(1);
				}

				m.OSTBase     = c.ostBaseFile;
				m.dictFile    = c.dictFile;
				m.outputBase  = c.outputBase;
				m.cfPeriod    = c.cfPeriod;
				m.numStates   = c.numStates;
				m.dictNum     = c.dictNum;
				m.dictExhFile = c.dictExhFile;
				m.colors = m.numStates;
				m.externalSATSolverFile = m.cadicalExhaustPath;

				m.run();
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(1);
			}
		} else {
			System.err.println("Usage: java Main -f <code>");
			System.exit(1);
		}
	}

	private void run() {
		Locale.setDefault(Locale.US);
		try {
			launch();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private String[][] baseDFATrans;
	private int ostSz;
	private int SBStrategy = 1;
	private String dimacsFile;
	private int p = 0;
	private int findCount = 0;
	private boolean findAllMode = false;
	private boolean iterativeMode = false;
	private boolean iterativeSolver = false;
	private boolean loopMode = false;
	private int amo = 1;
	private boolean backtrackingMode = false;
	private String externalSATSolverFile;

	private void launch() throws IOException {
		this.colors = this.numStates;

		// ----------------- Set Up Directories ----------------- //
		File folderPath = new File("myFiles");
		if (!folderPath.exists()) { folderPath.mkdir(); }
		File resPath   = new File(folderPath, "results"); if (!resPath.exists())   resPath.mkdir();
		File dictPath  = new File(folderPath, "dict");    if (!dictPath.exists())  dictPath.mkdir();
		File basePath  = new File(folderPath, "ostBase"); if (!basePath.exists())  basePath.mkdir();

		// ----------------- Read the DFA Transitions ----------------- //
		Path filePath = Paths.get(basePath.getAbsolutePath(), OSTBase);
		if (!Files.exists(filePath)) {
			System.err.println("ERROR: DFA transitions file not found: " + filePath);
			exit(1);
		}
		String[][] baseDFATrans = readDFATransitions(filePath.toString());
		int ostSz = baseDFATrans.length;

		// ----------------- Create Result Directory ----------------- //
		String timeStamp    = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Calendar.getInstance().getTime());
		String resultDirName= OSTBase.replaceAll("\\.txt$", "") + "_b" + outputBase + "_" + timeStamp;
		File   resultPath   = new File(resPath, resultDirName);
		if (!resultPath.exists()) resultPath.mkdir();

		File logFile = new File(resultPath, "log" + dictNum + ".txt");

		try (PrintWriter myLog = new PrintWriter(new FileWriter(logFile), true)) {
			File dictFileObj = new File(dictPath, dictFile);
			if (!dictFileObj.exists()) {
				System.err.println("ERROR: Dictionary file not found: " + dictFileObj);
				exit(1);
			}

			InputStream is = new FileInputStream(dictFileObj);

			long fullStartTime = System.currentTimeMillis();
			int curDFA = 1;

			// --- Build APTA --- //
			APTA apta = new APTA(is, outputBase, dictNum);

			// --- Build CG --- // 
			ConsistencyGraph cg = new ConsistencyGraph(apta, false, false, outputBase);

			try {
				// --- Build DimacsFile --- //
				dimacsFile = "tmpDimacsFile.cnf";
				DimacsFileGenerator dfg = new DimacsFileGenerator(apta, cg, colors, SBStrategy, p, dimacsFile, loopMode, outputBase, cfPeriod, baseDFATrans, ostSz);
				long dfgTime = System.currentTimeMillis();
				dfg.generateFile(amo);
				myLog.println("Dimacs file generated in " + (System.currentTimeMillis() - dfgTime) / 1000.0 + " seconds");
				long dimacsSizeMB = Files.size(Paths.get(dimacsFile)) / 1048576;
				myLog.println("Dimacs file size: " + dimacsSizeMB + " MB");
				myLog.println("SAT problem in dimacs format successfully generated");

				// --- Run SAT Solver --- //
				SATSolver solver = null;

				//String resultFile = new File(resultPath, "sol" + dictNum + ".txt").getAbsolutePath();
				myLog.println("\nCurrent DFA attempt: " + curDFA);

				int order = dfg.getExhaustOrder();
				//System.out.println("Main order: " + order);
				solver = new SATSolver(apta, colors, dimacsFile, 9999999, externalSATSolverFile, iterativeMode, iterativeSolver, resultPath, dictNum, order);
				myLog.println("SAT solver initialized. Vars: " + solver.nVars() + ", Constraints: " + solver.nConstraints());

				if (!solver.problemIsSatisfiable()){
					System.out.println("Cadical exhaust finished. Checking solutions against large dict file...");
				}

				// --- Check each solution against ExhDictFile --- //
				int passDFA = 0;
				int[] model = new int[dfg.getMaxVar()];
				int[] preModel = null;
				int[] yList = dfg.getY();
				int yMin = yList[0];
				int yMax = yList[yList.length - 1];
				List<List<Integer>> solList = new ArrayList<>();
				String time = null;


				BufferedReader br = new BufferedReader(new FileReader(new File(resultPath, "Exhaust_Result.txt")));
				String line;

				Pattern pattern = Pattern.compile("c New solution: (.* 0)");
				Pattern pattern2 = Pattern.compile("c Process time: (.* s)");

				FileWriter valLog = new FileWriter(new File(resultPath, "val_" + dictNum + ".txt"), false);
				int skipctr = 0;
				int skipstop = -1; // For skipping solutions already checked; unused
				while ((line = br.readLine()) != null) {
					Matcher matcher = pattern.matcher(line);
					time = null;
					if (matcher.find()) {
						curDFA++;
						line = br.readLine();
						Matcher matcher2 = pattern2.matcher(line);
						if (matcher2.find()) {
							time = matcher2.group(1);
						}
						skipctr++;
						if (skipctr < skipstop) {
							System.out.println("Skipping solution " + (curDFA - 1)); 
							continue;
						}
						String match = matcher.group(1);
						preModel = Arrays.stream(match.split(" ")).mapToInt(Integer::parseInt).toArray();

						for (int i = 0; i < dfg.getMaxVar(); i++) {
							model[i] = -(i + 1);
						}

						for (int i = 0; i < preModel.length - 1; i++) { //exclude 0 at end
							model[preModel[i] - 1] = preModel[i];
						}
						Automaton automaton = AutomatonBuilder.build(model, dfg, apta, colors, false, outputBase);
						boolean identical = false;
						boolean skip = false;
						if (!solList.isEmpty()) {
							for (List<Integer> sol : solList) {
								identical = true;
								for (Integer var : sol) {
									if (model[var - 1] < 0) {
										identical = false;
										break;
									}
								}
								if (identical) {
									myLog.println("DFA " + curDFA + " identical; current: " + passDFA + " of " + curDFA);
									System.out.println("DFA " + curDFA + " identical; current: " + passDFA + " of " + curDFA);
									System.out.println("IDENTICAL:" + automaton.toString());
									skip = true;
									break;
								}
							}
							if (skip)
								continue;
						}
						//System.out.println(Arrays.toString(model));
						//Automaton automaton = AutomatonBuilder.build(model, dfg, apta, colors, false, outputBase);

						// write the model solution into the result directory
						File modelFile = new File(resultPath, "ModelSolution");
						try (PrintWriter pw = new PrintWriter(modelFile)) {
							pw.print(automaton + "\n");
							pw.flush();
						}
						// validate against the model file in the same directory
						boolean valRes = validate(modelFile.getPath(), curDFA, valLog, dictPath);
						if (valRes) {
							ArrayList<Integer> tmp = new ArrayList<>();
							for (int i = yMin - 1; i < yMax; i++) {
								if (model[i] > 0) {
									tmp.add(model[i]);
								}
							}
							solList.add(tmp);

							System.out.println("\n-------------------------------\nSolution Transitions:" + tmp);
							System.out.println(automaton.toString());
							myLog.println("\n-------------------------------\nSolution Transitions:" + tmp);
							myLog.println(automaton.toString());
							passDFA++;
							System.out.println("DFA " + curDFA + " passed; current: " + passDFA + " of " + curDFA);
							System.out.println("Solution time: " + time);
							myLog.println("DFA " + curDFA + " passed; current: " + passDFA + " of " + curDFA);
							myLog.println("Solution time: " + time);
						} else {
							myLog.println("DFA " + curDFA + " failed; current: " + passDFA + " of " + curDFA);
							//System.out.println("DFA " + curDFA + " failed; current: " + passDFA + " of " + curDFA);
						}
					}
				}
				myLog.println("Full processing time: " + (System.currentTimeMillis() - fullStartTime) / 1000.0 + " seconds.");
			} catch (InterruptedException | ContradictionException | TimeoutException | ParseFormatException e) {
				throw new RuntimeException(e);
			}
		}
            // ----------------- Print Summary ----------------- //
		System.out.println("Finished.\n");
	}

	// ------------- DFA Transitions Parsing ------------- //
	// Parses the Ostrowski-base DFA file (e.g., msd_x.txt) provided by Walnut.
	public static String[][] readDFATransitions(String filename) throws IOException {
		// Maps to hold transitions for each state.
		Map<Integer, Map<Integer, List<String>>> transitionsMap = new HashMap<>();
		Map<Integer, String> stateOutputs = new HashMap<>();

		int maxStateId = -1;

		try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
			// --- Read Alphabet Line ---
			String alphabetLine = br.readLine();
			if (alphabetLine == null) {
				throw new IOException("File is empty!");
			}
			alphabetLine = alphabetLine.trim();
			if (alphabetLine.startsWith("{") && alphabetLine.endsWith("}")) {
				alphabetLine = alphabetLine.substring(1, alphabetLine.length() - 1);
			}
			String[] alphabet = alphabetLine.split(",");
			for (int i = 0; i < alphabet.length; i++) {
				alphabet[i] = alphabet[i].trim();
			}
			int alphabetSize = alphabet.length;
			System.out.println("Alphabet size: " + alphabetSize + " with symbols: " + Arrays.toString(alphabet));

			// --- Process Each State Block ---
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) {
					continue;
				}
				String[] headerParts = line.split("\\s+");
				if (headerParts.length < 2) {
					throw new IOException("Invalid header line: " + line);
				}
				int stateId = Integer.parseInt(headerParts[0]);
				String output = headerParts[1];
				stateOutputs.put(stateId, output);
				maxStateId = Math.max(maxStateId, stateId);

				Map<Integer, List<String>> stateTransitions = new HashMap<>();

				while ((line = br.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty()) {
						break;
					}
					String[] parts = line.split("->");
					if (parts.length < 2) {
						throw new IOException("Invalid transition line: " + line);
					}
					String label = parts[0].trim();
					int target = Integer.parseInt(parts[1].trim());
					maxStateId = Math.max(maxStateId, target);
					stateTransitions.computeIfAbsent(target, k -> new ArrayList<>()).add(label);
				}
				transitionsMap.put(stateId, stateTransitions);
			}
		}

		int numStates = maxStateId + 1;
		String[][] baseDFATrans = new String[numStates][numStates];

		for (Map.Entry<Integer, Map<Integer, List<String>>> entry : transitionsMap.entrySet()) {
			int stateId = entry.getKey();
			Map<Integer, List<String>> targetMap = entry.getValue();
			String[] row = new String[numStates];
			for (int target = 0; target < numStates; target++) {
				List<String> labels = targetMap.get(target);
				row[target] = (labels != null && !labels.isEmpty()) ? String.join(" ", labels) : null;
			}
			baseDFATrans[stateId] = row;
		}

		for (int state = 0; state < numStates; state++) {
			if (!transitionsMap.containsKey(state)) {
				baseDFATrans[state] = new String[numStates];
			}
		}

		System.out.println("\nDFA Transitions Matrix:");
		for (int state = 0; state < baseDFATrans.length; state++) {
			System.out.println("State " + state + ": " + Arrays.toString(baseDFATrans[state]));
		}

		return baseDFATrans;
	}

	private Boolean validate(String dfaFile, int curDFA, FileWriter myLog, File dictPath) throws IOException {
		boolean walnutDFA = false; // for validating DFAs created by Walnut
		boolean result = false;
		String resFile = dfaFile;

		try {
			myLog.append("CurDFA: " + curDFA + "\n");
			myLog.append("Building automaton from file \"" + resFile + "\".\n");
			Automaton automaton = new Automaton(new File(resFile), outputBase, walnutDFA);
			myLog.append(automaton.toString() + "\n\n");
			boolean correct = false;

			File dictFileObj = new File(dictPath, dictExhFile);

			if (!dictFileObj.exists()) {
				myLog.append("ERROR: Dictionary file not found: " + dictFileObj.getAbsolutePath() + "\n");
				System.out.println("ERROR: Dictionary file not found: " + dictFileObj.getAbsolutePath());
				return false;
			}
			try (BufferedReader br = new BufferedReader(new FileReader(dictFileObj))) {
				myLog.append("Parsing dictionary file \"" + dictExhFile + "\".\n"); 

				int lines = Integer.parseInt(br.readLine().split("\\s+")[0]);
				int mistakes = 0;
				int mistakesMax = 1;

				for (int line = 0; line < lines; line++) {
					String wordStr = br.readLine();
					List<String> word = new ArrayList<>(Arrays.asList(wordStr.split("\\s+")));

					assert word.size() == Integer.parseInt(word.get(1));

					int status = automaton.proceedWord(word.subList(2, word.size()), myLog);
					int flag = 0;
					for (int i = 0; i < outputBase; i++) {
						int wordInt = (word.get(0).charAt(0) - '0');
						if (status == i && wordInt == i) {
							flag = 1;
							break;
						}
					}
					if (flag == 0) {
						mistakes++;
						myLog.append(" MISTAKE at dictLine " + line + "\n");
					}
					if (mistakes >= 1) {
						break;
					}
				}
				if (mistakes < mistakesMax) {
					correct = true;
					result = true;
					myLog.append("Success- The automaton recognized dictionary correctly.\n");
				} else {
					correct = false;
					myLog.append("Fail - The automaton recognized dictionary INCORRECTLY.\n");
				}
				myLog.append("Mistakes found: " + mistakes + ". Mistakes allowed: " + mistakesMax + ".\n");
			} catch (IOException e) {
				System.out.println("Unexpected problem with file: " + e.getMessage());
			}
			myLog.flush();
			if (correct) {
				myLog.append("\nChecking for BFS-enumeration started.\n");
				if (new BFSChecker(automaton).check()) {
					System.out.println("BFS enumeration successful.");
					myLog.append("Success - The automaton is BFS-enumerated.\n");
				} else {
					System.out.println("BFS enumeration failure.");
					myLog.append("Fail - The automaton is NOT BFS-enumerated. \n");
				}
			}
			myLog.append("\n");
		} catch (IOException e) {
			System.out.println("Unexpected problem with file: " + e.getMessage());
		}
		myLog.flush();
		return result;
	}

	private class BFSChecker {
		Automaton automaton;
		Queue<Node> queue;
		boolean[] visited;
		List<String> alphabet;
		int expected;

		BFSChecker(Automaton automaton) {
			this.automaton = automaton;
			queue = new LinkedList<>();
			visited = new boolean[automaton.size()];
			alphabet = new ArrayList<>(automaton.getStart().getChildren().keySet());
			Collections.sort(alphabet);
			expected = 0;
		}

		boolean check() {
			queue.add(automaton.getStart());
			visited[automaton.getStart().getNumber()] = true;
			Node cur;
			while (!queue.isEmpty()) {
				cur = queue.remove();
				if (cur.getNumber() != expected++) {
					System.out.printf("Got state %d, but expected state %d\n", cur.getNumber(), expected - 1);
					return false;
				}
				for (String label : alphabet) {
					Node child = cur.getChild(label);
					if (child == null) {
						// Missing transition: adding a self-loop.
						cur.addChild(label, cur);
						System.out.printf("DFA state %d is missing transition for label \"%s\", adding self-loop.\n", cur.getNumber(), label);
						child = cur;
					}
					if (!visited[child.getNumber()]) {
						visited[child.getNumber()] = true;
						queue.add(child);
					}
				}
			}
			return true;
		}
	}

}
