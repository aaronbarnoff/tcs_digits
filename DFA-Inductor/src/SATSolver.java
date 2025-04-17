// SATSolver.java
import org.sat4j.minisat.SolverFactory;
import org.sat4j.reader.DimacsReader;
import org.sat4j.reader.ParseFormatException;
import org.sat4j.reader.Reader;
import org.sat4j.specs.*;

import java.io.*;
import java.util.Scanner;

public class SATSolver {

	private APTA apta;
	private int colors;
	private IProblem problem;
	private String dimacsFile;
	private String satSolverFile;
	private String ansLine = "";
	private int countVars, countClauses;
	private int timeout;
	private boolean iterativeMode, iterativeSolver;
	private File logDir;
	private int curDict;

	public SATSolver(
			APTA apta, int colors,
			String dimacsFile, int timeout,
			String satSolverFile,
			boolean iterativeMode, boolean iterativeSolver,
			File logDir, int N, int order
	) throws IOException, ContradictionException {
		this.apta = apta;
		this.colors = colors;
		this.dimacsFile = dimacsFile;
		this.timeout = timeout;
		this.satSolverFile = satSolverFile;
		this.iterativeMode = iterativeMode;
		this.iterativeSolver = iterativeSolver;
		this.logDir = logDir;
		this.curDict = N;
		parseDimacsHeader();
	}

	private void parseDimacsHeader() throws IOException {
		try (BufferedReader br = new BufferedReader(new FileReader(dimacsFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("p cnf ")) {
					String[] tok = line.split("\\s+");
					countVars = Integer.parseInt(tok[2]);
					countClauses = Integer.parseInt(tok[3]);
					break;
				}
			}
		}
	}

	private IProblem buildInternalSolver()
			throws ContradictionException, ParseFormatException, IOException
	{
		ISolver solver = SolverFactory.newDefault();
		if (timeout > 0) solver.setTimeout(timeout);
		Reader dimacs = new DimacsReader(solver);
		return dimacs.parseInstance(dimacsFile);
	}

	public boolean problemIsSatisfiable()
			throws TimeoutException, IOException, ParseFormatException, ContradictionException, InterruptedException
	{
		if (satSolverFile == null) {
			// built-in SAT4J
			problem = buildInternalSolver();
			return problem.isSatisfiable();
		}

		// external solver path is already correct; run directly
		// prepare log
		if (!logDir.exists()) logDir.mkdirs();
		File log = new File(logDir, "cadical_state_" + colors + "_dict_" + curDict + ".log");
		try (PrintWriter lw = new PrintWriter(new FileWriter(log, true), true)) {
			lw.println("DIMACS: " + new File(dimacsFile).getAbsolutePath());
			ProcessBuilder pb = new ProcessBuilder(
					satSolverFile,
					dimacsFile
			);
			pb.redirectErrorStream(true);
			lw.println("Running: " + String.join(" ", pb.command()));
			Process proc = pb.start();

			try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
				String ln;
				boolean sawSat = false;
				while ((ln = in.readLine()) != null) {
					lw.println(ln);
					if (ln.trim().equals("s SATISFIABLE")) sawSat = true;
					if (ln.startsWith("v ")) ansLine += ln.substring(2).trim() + " ";
					if (ln.contains("reached")) throw new TimeoutException();
				}
				proc.waitFor();
				lw.println("Exit code: " + proc.exitValue());
				return sawSat;
			}
		}
	}

	public int[] getModel() throws Exception {
		if (ansLine.isEmpty()) throw new Exception("Call problemIsSatisfiable() first.");
		String[] tok = ansLine.trim().split("\\s+");
		int[] m = new int[tok.length];
		for (int i = 0; i < tok.length; i++) m[i] = Integer.parseInt(tok[i]);
		return m;
	}

	public int nVars()        { return countVars; }
	public int nConstraints() { return countClauses; }
}