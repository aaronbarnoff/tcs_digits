// Main.java
import org.sat4j.reader.ParseFormatException;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.TimeoutException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.lang.System.exit;

public class Main {

	public static class Config {
		String OSTBase;
		String dictFile;
		int dictStart;
		int dictEnd;
		int dictStep;
		int minStateCnt;
		int maxStateCnt;
		int outputBase;
		String cfPeriod;
		int satTimeout;
	}

	private Map<String, Config> configs = new HashMap<>();
	private String cadicalExhaustPath;

	private void loadAllConfigs(Path cfgPath) throws IOException {
		try (BufferedReader br = Files.newBufferedReader(cfgPath)) {
			String line;
			String currentKey = null;
			Config cfg = null;

			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

				// top-level path
				if (currentKey == null && line.startsWith("CadicalExhaustPath:")) {
					String val = line.substring(line.indexOf(':') + 1).trim();
					if (val.endsWith(",")) val = val.substring(0, val.length() - 1).trim();
					if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
					cadicalExhaustPath = val;
					continue;
				}

				// start a config block
				if (line.endsWith(":")) {
					currentKey = line.substring(0, line.length() - 1).trim();
					cfg = new Config();
					continue;
				}
				// end of block
				if ((line.equals("{") || line.equals("}")) && "}".equals(line) && currentKey != null && cfg != null) {
					configs.put(currentKey, cfg);
					continue;
				}
				if (cfg == null) continue;

				// inside block: key = value
				String[] parts = line.split("=", 2);
				if (parts.length < 2) continue;
				String key = parts[0].trim();
				String val = parts[1].trim();
				if (val.endsWith(",")) val = val.substring(0, val.length() - 1).trim();
				if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
				cfg.satTimeout = 99999;
				switch (key) {
					case "ostBaseFile":
						cfg.OSTBase = val;
						break;
					case "dictFile":
						cfg.dictFile = val;
						break;
					case "dictStart":
						cfg.dictStart = Integer.parseInt(val);
						break;
					case "dictEnd":
						cfg.dictEnd = Integer.parseInt(val);
						break;
					case "dictStep":
						cfg.dictStep = Integer.parseInt(val);
						break;
					case "minStateCnt":
						cfg.minStateCnt = Integer.parseInt(val);
						break;
					case "maxStateCnt":
						cfg.maxStateCnt = Integer.parseInt(val);
						break;
					case "outputBase":
						cfg.outputBase = Integer.parseInt(val);
						break;
					case "cfPeriod":
						cfg.cfPeriod = val;
						break;
				}
			}
		}
	}

	public static void main(String... args) {
		if (args.length == 2 && args[0].equals("-f")) {
			String code = args[1];
			Main m = new Main();
			try {
				m.loadAllConfigs(Paths.get("config.txt"));
				Config c = m.configs.get(code);
				if (c == null) {
					System.err.println("No config for key: " + code);
					exit(1);
				}
				m.runWithConfig(c);
			} catch (Exception e) {
				e.printStackTrace();
				exit(1);
			}
		} else {
			System.err.println("Usage: java Main -f <configKey>");
			exit(1);
		}
	}

	private void runWithConfig(Config c) throws Exception {
		launch(
				c.OSTBase, c.dictFile,
				c.dictStart, c.dictEnd, c.dictStep,
				c.minStateCnt, c.maxStateCnt,
				c.outputBase, c.cfPeriod,
				c.satTimeout, cadicalExhaustPath
		);
	}

	private void launch(
			String OSTBase, String dictFile,
			int dictStart, int dictEnd, int dictStep,
			int minStateCnt, int maxStateCnt,
			int outputBase, String cfPeriod,
			int satTimeout, String externalSATSolver
	) throws Exception {
		// Prepare directories
		File root = new File("myFiles"); if (!root.exists()) root.mkdir();
		File resultsRoot = new File(root, "results"); if (!resultsRoot.exists()) resultsRoot.mkdir();
		File dictDir = new File(root, "dict"); if (!dictDir.exists()) dictDir.mkdir();
		File baseDir = new File(root, "ostBase"); if (!baseDir.exists()) baseDir.mkdir();

		// Load DFA transitions
		Path dfaPath = Paths.get(baseDir.getAbsolutePath(), OSTBase);
		if (!Files.exists(dfaPath)) { System.err.println("Missing DFA file: " + dfaPath); exit(1); }
		String[][] baseDFATrans = readDFATransitions(dfaPath.toString());
		int ostSz = baseDFATrans.length;

		// Create run directory
		String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		String runName = OSTBase.replaceAll("\\.txt$", "") + "_b" + outputBase + "_" + ts;
		File runDir = new File(resultsRoot, runName); if (!runDir.exists()) runDir.mkdir();

		// Create summary writer
		File summary = new File(runDir, "summary.txt");
		try (PrintWriter sumOut = new PrintWriter(new FileWriter(summary), true)) {
			sumOut.println("SAT/UNSAT times are approximated, check cadical output log for accurate times");
			int curState = minStateCnt;
			// Loop over dictionary sizes
			for (int N = dictStart; N <= dictEnd; N += dictStep) {
				// Load dictionary and APTA
				File df = new File(dictDir, dictFile);
				InputStream is = new FileInputStream(df);
				APTA apta = new APTA(is, outputBase, N);
				ConsistencyGraph cg = new ConsistencyGraph(apta, false, false, outputBase);

				boolean solved = false;
				// Try increasing state counts
				for (int colors = curState; colors <= maxStateCnt; colors++) {
					// Generate CNF
					String cnf = runDir.getAbsolutePath() + "/tmpDimacsFile.cnf";
					DimacsFileGenerator dfg = new DimacsFileGenerator(
							apta, cg, colors, 1, 0, cnf,
							false, outputBase, cfPeriod, baseDFATrans, ostSz
					);
					dfg.generateFile(1);

					// Solve with SATSolver
					SATSolver solver = new SATSolver(
							apta, colors, cnf, satTimeout,
							externalSATSolver, false, false,
							runDir, N, colors
					);
					long start = System.currentTimeMillis();
					boolean sat = solver.problemIsSatisfiable();
					double secs = (System.currentTimeMillis() - start) / 1000.0;

					if (sat) {
						sumOut.printf("Digit %d State %d SAT ~= %.2fs%n", N - 1, colors, secs);
						// Write solution
						int[] model = solver.getModel();
						Automaton aut = AutomatonBuilder.build(model, dfg, apta, colors, false, outputBase);
						try (PrintWriter solOut = new PrintWriter(new File(runDir, "sol" + N + ".txt"))) {
							solOut.println(aut);
						}
						curState = colors; // next N starts here
						solved = true;
						break; // stop colors loop
					} else {
						sumOut.printf("Digit %d State %d UNSAT ~= %.2fs%n", N - 1, colors, secs);
					}
				}

				if (!solved) {
					sumOut.printf("Digit %d: no solution up to %d states%n", N - 1, maxStateCnt);
					// keep curState unchanged for next N
				}

			}
		}

		System.out.println("Done. Results: " + runDir.getAbsolutePath());
	}


	// Utility method to format numbers (if needed)
	private String fineNumber(int number) {
		return (number < 10) ? "000" + number :
				number < 100 ? "00" + number :
						number < 1000 ? "0" + number : String.valueOf(number);
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

		// For states that never appeared, ensure the row is null-initialized.
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

}
