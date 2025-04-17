import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.*;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/*
Validator may require the walnut-generated DFA to be in the form:
	digraph G {
		rankdir = LR;
		node [shape = circle, label="0/0", fontsize=12]0;
		node [shape = circle, label="1/1", fontsize=12]1;
		...
		node [shape = circle, label="25/0", fontsize=12]25;
		node [shape = circle, label="26/0", fontsize=12]26;
		0 -> 0[ label = "0"];
		1 -> 3[ label = "0"];
		...
		25 -> 7[ label = "2"];
		26 -> 22[ label = "0"];
	}
 */
public class Validator {

	private int dictStart= 100002;
	private int dictEnd = 100002;
	int numOutputs = 2;
	boolean walnutDFA = false; //for validating DFAs created by Walnut

	private String automatonPath = "myFiles/validator/";
	private String dictionaryPath = "myFiles/validator/tmpDict";;
	private String logFile = "myFiles/validator";

	private int p = 0; //unused
	private boolean bfsMode = true;
	private boolean dfsMode = false; //unused

	private void launch(String... args) throws IOException {
		File valPath = new File("myFiles/validator");
		if (!valPath.exists())
			valPath.mkdir();
		CmdLineParser parser = new CmdLineParser(this);
		try {
			parser.parseArgument(args);
		} catch (CmdLineException e) {
			System.err.println(e.getMessage());
			System.err.print("Usage:");
			parser.printSingleLineUsage(System.err);
			System.err.println();
			parser.printUsage(System.err);
			return;
		}

		int cur = 1;
		int cutoff = 1;
		for (int N = dictStart; N <= dictEnd; N++) {
			while (true) {
				//String resFile =
				String resFile;
				if (walnutDFA)
					resFile = automatonPath + "walnut.txt";
				else
					resFile = automatonPath + "081_588_soltest.txt"; //automatonPath + N + "_" + cur +"_.txt";
				System.out.println("Checking result: " + resFile);
				if (!(new File(resFile).exists() && cur <= cutoff)) //cur < cutoff
				{
					System.out.println("Result not found");
					break;
				}

				FileWriter myLog = new FileWriter(new File(logFile,"val_" + N + "_" + cur + ".txt"), false);
				cur += 1;
				try {
					myLog.append("Building automaton from file \"" + resFile + "\".\n");
					Automaton automaton = new Automaton(new File(resFile), numOutputs, walnutDFA);
					myLog.append(automaton.toString() + "\n\n");
					boolean correct = false;

					String dictFile = dictionaryPath + N + ".txt";
					try (BufferedReader br = new BufferedReader(new FileReader(dictFile))) {

						myLog.append("Parsing dictionary file \"" + dictFile + "\".\n");

						int lines = Integer.parseInt(br.readLine().split("\\s+")[0]);
						int mistakes = 0;
						int mistakesMax = (int) Math.round((lines / 100.0) * p);

						for (int line = 0; line < lines; line++) {
							// <status> <len> [label ...]
							String wordStr = br.readLine();
							List<String> word = new ArrayList<>(Arrays.asList(wordStr.split("\\s+")));
							//myLog.append("Line: " + line + ". Status: " + word.get(0) + ". Len: " + word.get(1) + "\n");
							assert word.size() == Integer.parseInt(word.get(1));
							myLog.append("String: " + word.toString() + "\n");
							myLog.append("Ideal Result: OUTPUT " + (word.get(0)+"\n"));

							int status = automaton.proceedWord(word.subList(2, word.size()), myLog);
							int flag = 0;
							for (int i = 0; i < numOutputs; i++)
							{
								int wordInt = (word.get(0).charAt(0)-'0');
								if (status == i && wordInt == i) {
									flag = 1;
									break;
								}
							}
							if (flag == 0) {
								mistakes++;
								myLog.append("^ MISTAKE ^\n");
							}
						}
						if (mistakes <= mistakesMax) {
							correct = true;
							//System.out.println("Dict file recognized.");
							myLog.append("Success- The automaton recognized dictionary correctly.\n");
						} else {
							correct = false;
							System.out.println("Dict file not recognized.");
							myLog.append("Fail - The automaton recognized dictionary INCORRECTLY.\n");
						}
						myLog.append("Mistakes found: " + mistakes + ". Mistakes allowed: " + mistakesMax + ".\n");
					} catch (IOException e) {
						System.out.println("Some unexpected problem with file \"" + dictionaryPath + "\":" + e.getMessage());
					}
					myLog.flush();
					if (correct) {
						if (bfsMode) {
							myLog.append("\nChecking for BFS-enumeration started.\n");
							if (new BFSChecker(automaton).check()) {
								System.out.println("BFS enumeration successful.");
								myLog.append("Success - The automaton is BFS-enumerated.\n");
							} else {
								System.out.println("BFS enumeration failure.");
								myLog.append("Fail - The automaton is NOT BFS-enumerated. \n");
							}
						}
						if (dfsMode) {
							myLog.append("Checking for DFS-enumeration started.\n");
							if (new DFSChecker(automaton).check()) {
								myLog.append("The automaton is DFS-enumerated! Congrats :)\n");
							} else {
								System.out.println("The automaton is not DFS-enumerated! Too sad :(");
							}
						}
					}
					myLog.append("\n");
				} catch (IOException e) {
					System.out.println("Some unexpected problem with file \"" + automatonPath + "\":" + e.getMessage());
				}
				myLog.flush();
			}
		}
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
					System.out.printf("Got state %d, but expected state %d\n", cur.getNumber(), expected-1);
					return false;
				}
				for (String label : alphabet) {
					Node child = cur.getChild(label);
					if (child == null) {				//here is where validator fails because the state doesn't have two children
						cur.addChild(label, cur);		//treat it as a self loop
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

	private class DFSChecker {
		Automaton automaton;
		boolean visited[];
		int expected;
		List<String> alphabet;

		DFSChecker(Automaton automaton) {
			this.automaton = automaton;
			visited = new boolean[automaton.size()];
			alphabet = new ArrayList<>(automaton.getStart().getChildren().keySet());
			Collections.sort(alphabet);
			expected = 0;
		}

		boolean check() {
			return dfs(automaton.getStart());
		}

		boolean dfs(Node cur) {
			visited[cur.getNumber()] = true;
			boolean res = true;

			if (cur.getNumber() != expected++) {
				return false;
			}
			for (String label : alphabet) {
				Node child = cur.getChild(label);
				if (!visited[child.getNumber()]) {
					res &= dfs(child);
				}
			}
			return res;
		}
	}

	private void run(String... args) {
		Locale.setDefault(Locale.US);
		try {
			launch(args);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	public static void main(String... args) {
		new Validator().run(args);
	}
}
