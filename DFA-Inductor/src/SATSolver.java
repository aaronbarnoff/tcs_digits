import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SATSolver {

    public interface ModelHandler {
        boolean onModel(int[] model) throws Exception;
    }

    private final APTA apta;
    private final int colors;
    private final String dimacsFile;
    private final String solverPath;
    private final File logDir;
    private final int curDict;
    private final int orderArg;

    private int timeoutSec;

    private boolean called = false;
    private boolean satSeen = false;
    private boolean unsatSeen = false;
    private int[] firstModel = null;

    private int countVars = 0;
    private int countClauses = 0;

    public SATSolver(
            APTA apta,
            int colors,
            String dimacsFile,
            int timeoutSec,
            String solverPath,
            boolean iterativeMode,
            boolean iterativeSolver,
            File logDir,
            int curDict,
            int orderArg
    ) throws IOException {
        this.apta = apta;
        this.colors = colors;
        this.dimacsFile = dimacsFile;
        this.timeoutSec = timeoutSec;
        this.solverPath = solverPath;
        this.logDir = logDir;
        this.curDict = curDict;
        this.orderArg = orderArg;

        parseDimacsHeader();
    }

    private void parseDimacsHeader() throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(dimacsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("p cnf ")) {
                    String[] tok = line.trim().split("\\s+");
                    if (tok.length >= 4) {
                        countVars = Integer.parseInt(tok[2]);
                        countClauses = Integer.parseInt(tok[3]);
                    }
                    return;
                }
            }
        }
    }

    public boolean problemIsSatisfiable() throws Exception {
        called = true;
        satSeen = false;
        unsatSeen = false;
        firstModel = null;

        runSolver(new ModelHandler() {
            @Override
            public boolean onModel(int[] model) {
                if (firstModel == null) firstModel = model;
                return false;
            }
        });

        if (firstModel != null) return true;
        if (satSeen) return true;
        if (unsatSeen) return false;
        return false;
    }

    public void enumerateModels(ModelHandler handler) throws Exception {
        called = true;
        satSeen = false;
        unsatSeen = false;
        firstModel = null;
        runSolver(handler);
    }

    private void runSolver(ModelHandler handler) throws Exception {
        ensureLogDir();
        File logFile = (logDir == null) ? null : new File(logDir, logName());

        ProcessBuilder pb = buildCommand();
        pb.redirectErrorStream(true);

        PrintWriter log = null;
        try {
            if (logFile != null) log = new PrintWriter(new FileWriter(logFile, true), true);

            if (log != null) {
                log.println("DIMACS: " + new File(dimacsFile).getAbsolutePath());
                log.println("Command: " + String.join(" ", pb.command()));
            }

            Process p = pb.start();

            List<Integer> curModel = new ArrayList<>();
            boolean deliveredStop = false;

            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (log != null) log.println(line);

                    String t = line.trim();
                    if (t.equals("s SATISFIABLE")) satSeen = true;
                    if (t.equals("s UNSATISFIABLE")) unsatSeen = true;

                    if (t.startsWith("v")) {
                        String rest = (t.startsWith("v ") ? t.substring(2) : t.substring(1)).trim();
                        deliveredStop |= parseVLine(rest, curModel, handler);
                        if (deliveredStop) break;
                    }

                    if (t.equals("s UNKNOWN") || (t.contains("time limit") && t.contains("reached"))) {
                        throw new TimeoutException();
                    }
                }
            }

            boolean finished;
            if (deliveredStop) {
                p.destroy();
                finished = p.waitFor(5, TimeUnit.SECONDS);
                if (!finished) p.destroyForcibly();
            } else if (timeoutSec > 0) {
                finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    if (log != null) log.println("Timeout after " + timeoutSec + " seconds");
                    throw new TimeoutException();
                }
            } else {
                p.waitFor();
            }

            int code = p.exitValue();
            if (log != null) log.println("Exit code: " + code);

            if (code != 10 && code != 20) {
                throw new RuntimeException("Solver gave UNKNOWN exit code: " + code);
            }
        } finally {
            if (log != null) log.close();
        }
    }

    private boolean parseVLine(String rest, List<Integer> curModel, ModelHandler handler) throws Exception {
        if (rest.isEmpty()) return false;

        String[] tok = rest.split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            int lit = Integer.parseInt(tok[i]);
            if (lit == 0) {
                int[] model = new int[curModel.size()];
                for (int j = 0; j < curModel.size(); j++) model[j] = curModel.get(j);
                curModel.clear();

                boolean keepGoing = handler.onModel(model);
                if (!keepGoing) return true;
            } else {
                curModel.add(lit);
            }
        }
        return false;
    }

    private ProcessBuilder buildCommand() {
        if (isExhaust() && orderArg != 0) {
            return new ProcessBuilder(
                    solverPath,
                    "--order",
                    String.valueOf(orderArg),
                    dimacsFile
            );
        }
        return new ProcessBuilder(solverPath, dimacsFile);
    }

    private boolean isExhaust() {
        String exe = new File(solverPath).getName().toLowerCase();
        return exe.contains("exhaust");
    }

    private void ensureLogDir() {
        if (logDir != null && !logDir.exists()) logDir.mkdirs();
    }

    private String logName() {
        String tag = isExhaust() ? "exhaust" : "cadical";
        return tag + "_state_" + colors + "_dict_" + curDict + ".log";
    }

    public int[] getModel() throws Exception {
        if (!called) throw new Exception("Call problemIsSatisfiable() first.");
        if (firstModel == null) throw new Exception("No SAT model available.");
        return firstModel;
    }

    public void updateTimeout(int timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    public int nVars() {
        return countVars;
    }

    public int nConstraints() {
        return countClauses;
    }
}
