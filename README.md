This MinDFA implementation is based on DFA Inductor created by I. Zakirzyanov et. al at https://github.com/ctlab/DFA-Inductor

## Setup

* Run ```./compile-tools.sh``` to install cadical-exhaust.
* Run ```./runDFA.sh -c``` in DFA-Inductor to compile the MinDFA solver.

### Workflow for testing results from the paper
In DFA-Inductor, config.txt lists the various quadratic irrationals tested in the paper.

* To verify minimality of Walnut's DFAOs in Table 2, e.g., phi base 2, run ```./runDFA.sh -f 021 -s 0```.
* To test the minimality of the DFAOs in Table 3:
    1. Generate the digit file for an arbitrarily large number of digits (e.g., 100,000) using DigitGen.ipynb (SageMath) in folder DictFileGeneration.
        * Usage: ```Configure QIname, QI expression, base, digits```.
    2. Generate the large dictionary file for the DFAO from those digits using dictGenMT.c (or use dictGen.py for smaller dict sizes) in folder DictFileGeneration.
        * Usage: ```./dictGenMT digitFile dictSize base "c.f period" numThreads```
        * Usage E.g.: ```python3 dictGen.py -f dict_phi_b2_100.txt -q phi_b2_1M.txt -s 100 -e 100 -b 2 -pd '1'```
    3. Run ```./runDFA.sh -f 021 -s 1```
### General workflow for an arbitrary quadratic irrational
#### In Walnut:
1. If required, run Walnut commands (instructions in paper) to create the base DFA (Walnut generates the base DFA automatically during the creation of the Walnut DFAO).
   * Place it in DFA-Inductor/myFiles/ostBase.

#### In DictFileGeneration:

2. Configure DigitGen.ipynb (SageMath) to create the first x digits right of the point.
   Usage: ```Configure QIname, QI expression, base, digits```.

3. Pass that digit file as -q to dictGen.py to create the dictionary file.

   * Example Usage: ```python3 dictGen.py -f dict_phi_b2_100.txt -q phi_b2_1M.txt -s 100 -e 100 -b 2 -pd '1'```

        * This creates a dictionary file for phi in base 2, containing the Ostrowski representation for the first 100 digits (set s=e).

   * Try dictGenMT.c for dictionary sizes > 20k (e.g., if using -s 1 exhaust to verify DFAOs against a 100k dictionary size).

        * Usage: ```./dictGenMT digitFile dictSize base "c.f period" numThreads```

#### In DFA-Inductor:

4. Place the dictionary file in DFA-Inductor/myFiles/dict.

5. Configure config.txt for desired test.

   * Example for phi in base 2:
   
```    
phi_b2:                                         # Name of test passed as -f to runDFA.sh
{
    ostBaseFile = "msd_phi.txt"                 # OstBase DFA file from step 1
    dictFile    = "dict_phi_b2_999.txt"         # Dictionary file from step 3
    outputBase  = 2                             # Desired base
    cfPeriod    = "1"                           # Period of the continued fraction

    # regular
    dictStart   = 1                             # Starting dictionary size
    dictEnd     = 998                           # Ending dictionary size
    dictStep    = 1                             # Step size
    minStateCnt = 1                             # Starting number of states to solve for
    maxStateCnt = 8                             # Final number of states to solve for (set to 999 if unknown)
    satTimeout  = 0                             # Timeout sent to cadical

    # exhaust
    numStates   = 8                             # Use this number of states to create the DFA
    dictNum     = 54                            # Use this dictionary size to create the DFA
    dictExhFile = "dict_phi_b2_100k.txt"        # Dictionary set used to verify the DFA against
}
```

6. (a) If trying to reproduce Walnut's DFAO and potentially see if a smaller DFAO exists.

   * For typical config settings, this starts at the minimum number of states, and climbs up to Walnut DFAO's state count.

   * Pass -s 0 to runDFA.sh to use the "regular" params in the config file.

        * Example: ```./runDFA.sh -f phi_b2 -s 0```

6. (b) If trying to search for a DFAO at a given number of states that correctly computes the digits up to the desired amount.

   * Pass -s 1 to runDFA.sh to use the "exhaust" params in the config file.

        * Example: ```./runDFA.sh -f phi_b2 -s 1```