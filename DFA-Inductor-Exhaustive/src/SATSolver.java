import org.sat4j.minisat.SolverFactory;
import org.sat4j.reader.DimacsReader;
import org.sat4j.reader.ParseFormatException;
import org.sat4j.reader.Reader;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IProblem;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.TimeoutException;

import java.io.*;
import java.util.Scanner;

public class SATSolver {

	private APTA apta;
	private int colors;
	private int vertices;
	private IProblem problem;
	private String dimacsFile = null;
	private String satSolverFile = null;
	private String ansLine = "";
	private int countClauses;
	private int countVars;
	private int timeout = 0;
	private boolean problemIsSatisfiableCalled = false;
	private boolean iterativeMode;
	private boolean iterativeSolver;
	private Process process;
	private File logFilePath;
	private int order;
	private boolean first;
	private Scanner sc;
	private BufferedWriter bw;
	private int curDict;

	public SATSolver(APTA apta, int colors, String dimacsFile, int timeout, String satSolverFile,
					 boolean iterativeMode, boolean iterativeSolver, File logFilePath, int N, int order)
			throws ContradictionException, IOException {
		init(apta, colors, dimacsFile, satSolverFile, iterativeMode, iterativeSolver, logFilePath, N, order);
		this.timeout = timeout;
	}

	private void init(APTA apta, int colors, String dimacsFile, String satSolverFile,
					  boolean iterativeMode, boolean iterativeSolver, File logFilePath, int N, int order)
			throws IOException {
		this.apta = apta;
		this.vertices = apta.getSize();
		this.dimacsFile = dimacsFile;
		this.colors = colors;
		this.satSolverFile = satSolverFile;
		this.iterativeMode = iterativeMode;
		this.iterativeSolver = iterativeSolver;
		this.first = true;
		this.logFilePath = logFilePath;
		this.curDict = N;
		this.order = order;

		try (BufferedReader br = new BufferedReader(new FileReader(dimacsFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("p cnf ")) {
					String[] tmp = line.split(" ");
					countVars = Integer.parseInt(tmp[2]);
					countClauses = Integer.parseInt(tmp[3]);
					break;
				}
			}
		}
	}

	private IProblem build() throws ContradictionException, ParseFormatException, IOException {
		ISolver solver = SolverFactory.newDefault();
		if (timeout > 0) {
			solver.setTimeout(timeout);
		}
		Reader reader = new DimacsReader(solver);
		return reader.parseInstance(dimacsFile);
	}

	/**
	 * Checks if the SAT problem is satisfiable.
	 * If satSolverFile is null, it uses SAT4J's built-in solver.
	 * Otherwise, it calls the external solver directly.
	 */
	public boolean problemIsSatisfiable() throws TimeoutException, IOException,
			ParseFormatException, ContradictionException, InterruptedException {
		problemIsSatisfiableCalled = true;
		if (satSolverFile == null) {
			problem = build();
			return problem.isSatisfiable();
		} else {
			if (!iterativeMode && !iterativeSolver) {
				File dimacsFileObj = new File(dimacsFile);
				String dimacsInfo = "Using DIMACS file: " + dimacsFileObj.getAbsolutePath()
						+ " (size: " + dimacsFileObj.length() + " bytes)";

				if (!logFilePath.exists()) {
					logFilePath.mkdirs();
				}
				File logFile = new File(logFilePath, "Exhaust_Result.txt");
				try (PrintWriter logWriter = new PrintWriter(new FileWriter(logFile, true), true)) {
					logWriter.println(dimacsInfo);
					logWriter.println("Solver order " + order); // Unused in regular cadical exhaust
					ProcessBuilder pb = new ProcessBuilder(
							satSolverFile,
							dimacsFile,
							//"--order", // Unused in regular cadical exhaust
							String.valueOf(order)
					);
					pb.redirectErrorStream(true);

					logWriter.println("Executing external solver: " + String.join(" ", pb.command()));
					Process process = pb.start();

					try (BufferedReader br = new BufferedReader(
							new InputStreamReader(process.getInputStream()))) {
						String line;
						boolean isSat = false;
						while ((line = br.readLine()) != null) {
							logWriter.println(line);
							if (line.trim().equals("s SATISFIABLE")) {
								isSat = true;
							}
							if (line.startsWith("v")) {
								ansLine += line.substring(2).trim() + " ";
							}
							if (line.contains("c time limit") && line.contains("reached")) {
								throw new TimeoutException();
							}
						}
						int exitCode = process.waitFor();
						logWriter.println("External solver exited with code: " + exitCode);
						return !ansLine.isEmpty();
					}
				}
			} else {
				if (first) {
					ProcessBuilder pb = new ProcessBuilder(satSolverFile, Integer.toString(countVars));
					process = pb.start();
					sc = new Scanner(new InputStreamReader(process.getInputStream()));
					bw = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
					try (BufferedReader brDimacs = new BufferedReader(new FileReader(dimacsFile))) {
						String line;
						while ((line = brDimacs.readLine()) != null) {
							if (line.startsWith("p cnf ")) continue;
							bw.write(line);
							bw.newLine();
						}
						bw.write("solve " + timeout);
						bw.newLine();
						bw.flush();
						line = sc.nextLine();
						if (line.equals("SAT")) {
							line = sc.nextLine();
							ansLine = line.substring(2).trim() + " ";
							if (iterativeSolver) {
								bw.write("halt");
								bw.newLine();
								bw.flush();
							}
						} else if (line.equals("UNKNOWN")) {
							bw.write("halt");
							bw.newLine();
							bw.flush();
							throw new TimeoutException();
						} else if (line.equals("UNSAT")) {
							bw.write("halt");
							bw.newLine();
							bw.flush();
						}
						first = false;
						return !ansLine.isEmpty();
					}
				} else {
					try (BufferedReader brDimacs = new BufferedReader(new FileReader(dimacsFile))) {
						ansLine = "";
						String prevLine = "";
						String line = brDimacs.readLine();
						while (line != null) {
							prevLine = line;
							line = brDimacs.readLine();
						}
						bw.write(prevLine);
						bw.newLine();
						bw.write("solve " + timeout);
						bw.newLine();
						bw.flush();
						line = sc.nextLine();
						if (line.equals("SAT")) {
							line = sc.nextLine();
							ansLine = line.substring(2).trim() + " ";
						} else if (line.equals("UNKNOWN")) {
							bw.write("halt");
							bw.newLine();
							bw.flush();
							throw new TimeoutException();
						} else if (line.equals("UNSAT")) {
							bw.write("halt");
							bw.newLine();
							bw.flush();
						}
						return !ansLine.isEmpty();
					}
				}
			}
		}
	}

	/**
	 * Must be called after problemIsSatisfiable().
	 * Returns the SAT model (as an array of integers).
	 */
	public int[] getModel() throws Exception {
		if (!problemIsSatisfiableCalled) {
			throw new Exception("Call problemIsSatisfiable() first.");
		}
		int[] model;
		if (satSolverFile == null) {
			model = problem.model();
		} else {
			String[] strings = ansLine.trim().split("\\s+");
			model = new int[strings.length];
			for (int i = 0; i < strings.length; i++) {
				model[i] = Integer.parseInt(strings[i]);
			}
		}
		return model;
	}

	public void updateTL(int timeout) {
		this.timeout = timeout;
	}

	public int nVars() {
		return countVars;
	}

	public int nConstraints() {
		return countClauses;
	}
}
