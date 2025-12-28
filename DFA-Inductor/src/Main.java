// Main.java
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.exit;

public class Main {

    enum Mode { EXHAUST, DIRECT }
    private enum SolverMode { REGULAR, EXHAUST }

    static class Config {
        Mode mode;
        String ostBaseFile;
        String dictFile;
        int outputBase;
        String cfPeriod;
        Integer numStates;
        Integer dictNum;
        String  dictExhFile;
        Integer dictStart;
        Integer dictEnd;
        Integer dictStep;
        Integer minStateCnt;
        Integer maxStateCnt;
        Integer satTimeout;
        String cadicalExhaustPath;
        String cadicalPath;
    }

	private static final String CADICAL_EXHAUST_PATH = "solvers/cadical-exhaust/build/cadical-exhaust";
	private static final String CADICAL_PATH         = "solvers/cadical-exhaust/build/cadical";

    private final Map<String, Config> configs = new HashMap<>();

    public static void main(String... args) {
        String code = null;
        SolverMode mode = SolverMode.REGULAR;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-f".equals(a) && i + 1 < args.length) {
                code = args[++i];
            } else if ("-s".equals(a) && i + 1 < args.length) {
                mode = "1".equals(args[++i]) ? SolverMode.EXHAUST : SolverMode.REGULAR;
            }
        }
        if (code == null) {
            System.err.println("Usage: java Main -f <code> [-s 0|1]");
            System.exit(1);
        }
        Main m = new Main();
        try {
            m.loadAllConfigs(Paths.get("config.txt"));
            Config c = m.configs.get(code);
            if (c == null) {
                System.err.println("No config for key: " + code);
                System.exit(1);
            }
            if (mode == SolverMode.REGULAR) {
                if (c.dictStart   == null) c.dictStart   = (c.dictNum != null ? c.dictNum : 1);
                if (c.dictEnd     == null) c.dictEnd     = c.dictStart;
                if (c.dictStep    == null) c.dictStep    = 1;
                if (c.minStateCnt == null) c.minStateCnt = 1;
                if (c.maxStateCnt == null) c.maxStateCnt = (c.numStates != null ? c.numStates : 32);
                if (c.satTimeout  == null) c.satTimeout  = 0;
            }
            if (mode == SolverMode.EXHAUST) {
                m.runExhaust(c);
            } else {
                m.runDirect(c);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void runExhaust(Config c) throws Exception {
        Locale.setDefault(Locale.US);
        File root = new File("myFiles"); if (!root.exists()) root.mkdir();
        File resPath = new File(root, "results"); if (!resPath.exists()) resPath.mkdir();
        File dictPath = new File(root, "dict"); if (!dictPath.exists()) dictPath.mkdir();
        File basePath = new File(root, "ostBase"); if (!basePath.exists()) basePath.mkdir();

        Path baseFile = Paths.get(basePath.getAbsolutePath(), c.ostBaseFile);
        if (!Files.exists(baseFile)) 
        { 
            System.err.println("Missing DFA file: " + baseFile); 
            exit(1); 
        }
        String[][] baseDFATrans = readDFATransitions(baseFile.toString());
        int ostSz = baseDFATrans.length;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Calendar.getInstance().getTime());
        String resultDirName = c.ostBaseFile.replaceAll("\\.txt$", "") + "_b" + c.outputBase + "_" + ts;
        
        // ------
        // If already have solutions computed, direct result dir to the folder containing the solution file 
        File resultPath = new File(resPath, resultDirName); 
        if (!resultPath.exists()) 
            resultPath.mkdir();
        File logFile = new File(resultPath, "log" + c.dictNum + ".txt");
        //File resultPath = new File("/mnt/e/Msc New/tcs_tmp/tcs_digits/DFA-Inductor/myFiles/results/msd_t081_b2_2025-12-26_22-06-25");
        //File logFile = new File(resultPath, "log" + c.dictNum + "_2.txt");
        // ------

        try (PrintWriter myLog = new PrintWriter(new FileWriter(logFile), true)) 
        {
            File dictFileObj = new File(dictPath, c.dictFile);
            if (!dictFileObj.exists()) 
            { 
                System.err.println("Missing dict: " + dictFileObj); 
                exit(1); 
            }
            try (InputStream is = new FileInputStream(dictFileObj)) {
                long fullStartTime = System.currentTimeMillis();
                int curDFA = 0;

                APTA apta = new APTA(is, c.outputBase, c.dictNum);
                ConsistencyGraph cg = new ConsistencyGraph(apta, false, false, c.outputBase);

                String cnf = "tmpDimacsFile.cnf";
                DimacsFileGenerator dfg = new DimacsFileGenerator(
                        apta, cg, c.numStates, 1, 0, cnf,
                        false, c.outputBase, c.cfPeriod, baseDFATrans, ostSz
                );

                long t0 = System.currentTimeMillis();
                dfg.generateFile(1);
                myLog.println("Dimacs file generated in " + (System.currentTimeMillis() - t0) / 1000.0 + " s");
                myLog.println("SAT problem generated");

                int order = dfg.getExhaustOrder();
                SATSolver solver = new SATSolver(
                        apta, c.numStates, cnf, 0,
                        CADICAL_EXHAUST_PATH, false, false,
                        resultPath, c.dictNum, order
                );
                myLog.println("SAT solver initialized. Vars: " + solver.nVars() + ", Constraints: " + solver.nConstraints());

                // -------------
                // If already have solutions computed, comment out these lines and direct result dir to the folder containing the solution file 
                boolean sat = solver.problemIsSatisfiable();
                if (!sat) 
                {
                    System.out.println("cadical-exhaust finished. Checking solutions against large dict file...");
                }
                // ------------
                
                int passDFA = 0;
                int[] model = new int[dfg.getMaxVar()];
                int[] preModel;
                int[] yList = dfg.getY();
                int yMin = yList[0];
                int yMax = yList[yList.length - 1];
                List<List<Integer>> solList = new ArrayList<>();
                String time = null;

                try (BufferedReader br = new BufferedReader(new FileReader(new File(resultPath, "exhaust_state_" + c.numStates + "_dict_" + c.dictNum + ".log")));
                     FileWriter valLog = new FileWriter(new File(resultPath, "val_" + c.dictNum + ".txt"), false))
                {
                    String line;
                    Pattern pSol = Pattern.compile("c New solution: (.* 0)");
                    Pattern pTime = Pattern.compile("c Process time: (.* s)");
                    while ((line = br.readLine()) != null) 
                    {
                        Matcher mSol = pSol.matcher(line);
                        if (mSol.find()) 
                        {
                            curDFA++;
                            line = br.readLine();
                            Matcher mTime = pTime.matcher(line);
                            if (mTime.find()) 
                                time = mTime.group(1);
                            String match = mSol.group(1);
                            preModel = Arrays.stream(match.split(" ")).mapToInt(Integer::parseInt).toArray();
                            
                            for (int i = 0; i < dfg.getMaxVar(); i++)
                                model[i] = -(i + 1);
                            
                            for (int i = 0; i < preModel.length - 1; i++) 
                                model[preModel[i] - 1] = preModel[i];
                            
                            Automaton automaton = AutomatonBuilder.build(model, dfg, apta, c.numStates, false, c.outputBase);
                            boolean skip = false;
                            if (!solList.isEmpty()) 
                            {
                                for (List<Integer> sol : solList) 
                                {
                                    boolean identical = true;
                                    for (Integer var : sol) 
                                    {
                                        if (model[var - 1] < 0) 
                                        { 
                                            identical = false; 
                                            break; 
                                        }
                                    }
                                    if (identical) 
                                    {
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
                            File modelFile = new File(resultPath, "ModelSolution");
                            try (PrintWriter pw = new PrintWriter(modelFile)) 
                            {
                                pw.print(automaton + "\n");
                            }
                            boolean valRes = validate(modelFile.getPath(), curDFA, valLog, dictPath, c.dictExhFile, c.outputBase);
                            if (valRes) 
                            {
                                ArrayList<Integer> tmp = new ArrayList<>();
                                for (int i = yMin - 1; i < yMax; i++) 
                                    if (model[i] > 0) 
                                        tmp.add(model[i]);
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
                            }
                        }
                    }
                }
                myLog.println("Full processing time: " + (System.currentTimeMillis() - fullStartTime) / 1000.0 + " s");
            }
        }
        System.out.println("Finished.");
    }

    private void runDirect(Config c) throws Exception {
        Locale.setDefault(Locale.US);
        File root = new File("myFiles"); if (!root.exists()) root.mkdir();
        File resultsRoot = new File(root, "results"); if (!resultsRoot.exists()) resultsRoot.mkdir();
        File dictDir = new File(root, "dict"); if (!dictDir.exists()) dictDir.mkdir();
        File baseDir = new File(root, "ostBase"); if (!baseDir.exists()) baseDir.mkdir();

        Path dfaPath = Paths.get(baseDir.getAbsolutePath(), c.ostBaseFile);
        if (!Files.exists(dfaPath)) 
        {
            System.err.println("Missing DFA file: " + dfaPath); 
            exit(1); 
        }
        String[][] baseDFATrans = readDFATransitions(dfaPath.toString());
        int ostSz = baseDFATrans.length;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String runName = c.ostBaseFile.replaceAll("\\.txt$", "") + "_b" + c.outputBase + "_" + ts;
        File runDir = new File(resultsRoot, runName); if (!runDir.exists()) runDir.mkdir();
        File summary = new File(runDir, "summary.txt");

		System.out.println("Results dir: " + runDir.getAbsolutePath());
		System.out.println("Summary file: " + summary.getAbsolutePath());
		System.out.printf("Config: dictFile=%s dictStart=%d dictEnd=%d dictStep=%d minState=%d maxState=%d timeout=%d%n",
			c.dictFile, c.dictStart, c.dictEnd, c.dictStep, c.minStateCnt, c.maxStateCnt, c.satTimeout); System.out.flush();

        try (PrintWriter sumOut = new PrintWriter(new FileWriter(summary), true)) 
        {
            sumOut.println("See SAT solver log for exact times.");
            int curState = c.minStateCnt;

            for (int N = c.dictStart; N <= c.dictEnd; N += c.dictStep) 
			{
				//System.out.printf("N=%d (digit %d) starting, curState=%d%n", N, (N - 1), curState); System.out.flush();
                File df = new File(dictDir, c.dictFile);
                try (InputStream is = new FileInputStream(df)) 
                {
					APTA apta = new APTA(is, c.outputBase, N);
                    ConsistencyGraph cg = new ConsistencyGraph(apta, false, false, c.outputBase);
                    boolean solved = false;

                    for (int colors = curState; colors <= c.maxStateCnt; colors++) 
					{      
                        System.out.printf("N=%d (digit %d) starting, curState=%d%n", N, (N - 1), curState); System.out.flush();                 
						String cnf = runDir.getAbsolutePath() + "/tmpDimacsFile.cnf";
                        DimacsFileGenerator dfg = new DimacsFileGenerator(
                                apta, cg, colors, 1, 0, cnf,
                                false, c.outputBase, c.cfPeriod, baseDFATrans, ostSz);
                        dfg.generateFile(1);

                        SATSolver solver = new SATSolver(
                                apta, colors, cnf, c.satTimeout,
                                CADICAL_PATH, false, false,
                                runDir, N, colors);

                        long t0 = System.currentTimeMillis();
                        boolean sat = solver.problemIsSatisfiable();
                        double secs = (System.currentTimeMillis() - t0) / 1000.0;

                        if (sat) 
                        {
                            sumOut.printf("Digit %d State %d SAT ~= %.2fs%n", N - 1, colors, secs);
                            int[] model = solver.getModel();
                            Automaton aut = AutomatonBuilder.build(model, dfg, apta, colors, false, c.outputBase);
                            try (PrintWriter solOut = new PrintWriter(new File(runDir, "sol" + N + ".txt"))) 
                            {
                                solOut.println(aut);
                            }
                            curState = colors;
                            solved = true;
                            break;
                        } else {
                            sumOut.printf("Digit %d State %d UNSAT ~= %.2fs%n", N - 1, colors, secs);
                        }
                    }
                    if (!solved) {
                        sumOut.printf("Digit %d: no solution up to %d states%n", N - 1, c.maxStateCnt);
                    }
                }
            }
        }
        System.out.println("Done. Results: " + runDir.getAbsolutePath());
    }

    private void loadAllConfigs(Path cfgPath) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(cfgPath)) {
            String line;
            String currentKey = null;
            Config cfg = null;
            while ((line = br.readLine()) != null) 
            {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) 
                    continue;
                if (line.endsWith(":")) 
                {
                    currentKey = line.substring(0, line.length() - 1).trim();
                    cfg = new Config();
                    continue;
                }
                if (line.equals("{") || line.equals("}")) 
                {
                    if (line.equals("}") && currentKey != null && cfg != null) 
                    {
                        if (cfg.mode == null) 
                        {
                            if (cfg.dictNum != null && cfg.numStates != null) 
                                cfg.mode = Mode.EXHAUST;
                            else cfg.mode = Mode.DIRECT;
                        }
                        configs.put(currentKey, cfg);
                        currentKey = null;
                        cfg = null;
                    }
                    continue;
                }
                if (cfg == null) 
                    continue;
                String[] parts = line.split("=", 2);
                if (parts.length < 2) 
                    continue;
                String key = parts[0].trim();
                String val = stripCommaAndQuotes(parts[1].trim());
                switch (key) {
                    case "mode":        cfg.mode = "exhaust".equalsIgnoreCase(val) ? Mode.EXHAUST : Mode.DIRECT; break;
                    case "ostBaseFile": cfg.ostBaseFile = val; break;
                    case "dictFile":    cfg.dictFile    = val; break;
                    case "outputBase":  cfg.outputBase  = parseIntSafe(val); break;
                    case "cfPeriod":    cfg.cfPeriod    = val; break;
                    case "numStates":   cfg.numStates   = parseIntSafe(val); break;
                    case "dictNum":     cfg.dictNum     = parseIntSafe(val); break;
                    case "dictExhFile": cfg.dictExhFile = val; break;
                    case "dictStart":   cfg.dictStart   = parseIntSafe(val); break;
                    case "dictEnd":     cfg.dictEnd     = parseIntSafe(val); break;
                    case "dictStep":    cfg.dictStep    = parseIntSafe(val); break;
                    case "minStateCnt": cfg.minStateCnt = parseIntSafe(val); break;
                    case "maxStateCnt": cfg.maxStateCnt = parseIntSafe(val); break;
                    case "satTimeout":  cfg.satTimeout  = parseIntSafe(val); break;
                }
            }
        }
    }

    private static String stripCommaAndQuotes(String v) {
        String val = v;
        if (val.contains("//")) val = val.substring(0, val.indexOf("//")).trim();
        if (val.endsWith(",")) val = val.substring(0, val.length() - 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
        return val;
    }

    private static Integer parseIntSafe(String s) {
        return Integer.parseInt(s.replaceAll("\\D", ""));
    }

    private static Boolean validate(String dfaFile, int curDFA, FileWriter myLog, File dictPath, String dictExhFile, int outputBase) throws IOException {
        boolean walnutDFA = false;
        boolean result = false;
        String resFile = dfaFile;
        try {
            myLog.append("CurDFA: " + curDFA + "\n");
            myLog.append("Building automaton from file \"" + resFile + "\".\n");
            Automaton automaton = new Automaton(new File(resFile), outputBase, walnutDFA);
            myLog.append(automaton.toString() + "\n\n");
            boolean correct = false;
            File dictFileObj = new File(dictPath, dictExhFile);
            if (!dictFileObj.exists()) 
            {
                myLog.append("ERROR: Dictionary file not found: " + dictFileObj.getAbsolutePath() + "\n");
                System.out.println("ERROR: Dictionary file not found: " + dictFileObj.getAbsolutePath());
                return false;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(dictFileObj))) 
            {
                myLog.append("Parsing dictionary file \"" + dictExhFile + "\".\n");
                int lines = Integer.parseInt(br.readLine().split("\\s+")[0]);
                int mistakes = 0;
                int mistakesMax = 1;
                for (int line = 0; line < lines; line++) 
                {
                    String wordStr = br.readLine();
                    List<String> word = new ArrayList<>(Arrays.asList(wordStr.split("\\s+")));
                    assert word.size() == Integer.parseInt(word.get(1));
                    int status = automaton.proceedWord(word.subList(2, word.size()), myLog);
                    int flag = 0;
                    for (int i = 0; i < outputBase; i++) 
                    {
                        int wordInt = (word.get(0).charAt(0) - '0');
                        if (status == i && wordInt == i) 
                        { 
                            flag = 1; 
                            break; 
                        }
                    }
                    if (flag == 0) 
                    {
                        mistakes++;
                        myLog.append(" MISTAKE at dictLine " + line + "\n");
                    }
                    if (mistakes >= 1) 
                        break;
                }
                if (mistakes < mistakesMax) 
                {
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
                if (new BFSChecker(automaton).check()) 
                {
                    System.out.println("BFS enumeration successful.");
                    myLog.append("Success - The automaton is BFS-enumerated.\n");
                } else {
                    System.out.println("BFS enumeration failure.");
                    myLog.append("Fail - The automaton is NOT BFS-enumerated.\n");
                }
            }
            myLog.append("\n");
        } catch (IOException e) {
            System.out.println("Unexpected problem with file: " + e.getMessage());
        }
        myLog.flush();
        return result;
    }

    private static class BFSChecker {
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
                        cur.addChild(label, cur);
                        System.out.printf("DFA state %d missing transition for label \"%s\", adding self-loop.\n", cur.getNumber(), label);
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

    public static String[][] readDFATransitions(String filename) throws IOException {
        Map<Integer, Map<Integer, List<String>>> transitionsMap = new HashMap<>();
        Map<Integer, String> stateOutputs = new HashMap<>();
        int maxStateId = -1;
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String alphabetLine = br.readLine();
            if (alphabetLine == null) throw new IOException("File is empty");
            alphabetLine = alphabetLine.trim();
            if (alphabetLine.startsWith("{") && alphabetLine.endsWith("}")) {
                alphabetLine = alphabetLine.substring(1, alphabetLine.length() - 1);
            }
            String[] alphabet = alphabetLine.split(",");
            for (int i = 0; i < alphabet.length; i++) alphabet[i] = alphabet[i].trim();
            System.out.println("Alphabet size: " + alphabet.length + " with symbols: " + Arrays.toString(alphabet));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] headerParts = line.split("\\s+");
                if (headerParts.length < 2) throw new IOException("Invalid header line: " + line);
                int stateId = Integer.parseInt(headerParts[0]);
                String output = headerParts[1];
                stateOutputs.put(stateId, output);
                maxStateId = Math.max(maxStateId, stateId);
                Map<Integer, List<String>> stateTransitions = new HashMap<>();
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) break;
                    String[] parts = line.split("->");
                    if (parts.length < 2) throw new IOException("Invalid transition line: " + line);
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
        for (Map.Entry<Integer, Map<Integer, List<String>>> e : transitionsMap.entrySet()) {
            int stateId = e.getKey();
            Map<Integer, List<String>> targetMap = e.getValue();
            String[] row = new String[numStates];
            for (int target = 0; target < numStates; target++) {
                List<String> labels = targetMap.get(target);
                row[target] = (labels != null && !labels.isEmpty()) ? String.join(" ", labels) : null;
            }
            baseDFATrans[stateId] = row;
        }
        for (int state = 0; state < numStates; state++) if (!transitionsMap.containsKey(state)) baseDFATrans[state] = new String[numStates];
        System.out.println("\nDFA Transitions Matrix:");
        for (int state = 0; state < baseDFATrans.length; state++) System.out.println("State " + state + ": " + Arrays.toString(baseDFATrans[state]));
        return baseDFATrans;
    }
}
