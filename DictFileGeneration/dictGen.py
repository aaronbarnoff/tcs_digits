#!/usr/bin/env python3
import argparse
import os
import time
# -f [outputPrefix] -q [digitFileName] -s [startDict] -e [endDict] -b [base] -pd [c.f. period]
# python3 dictGen.py -f dict -q phi_b2_1M.txt -s 100 -e 100 -b 2 -pd '1'

# Use dictGenMT for large dict files e.g. 100k digits

# Ensure required directories exist
os.makedirs("Digits", exist_ok=True)
os.makedirs("Results", exist_ok=True)

# Argument parsing
parser = argparse.ArgumentParser(
    description="Generate Ostrowski dictionaries for a quadratic irrational",
    formatter_class=argparse.ArgumentDefaultsHelpFormatter
)
parser.add_argument("-f", type=str, default="dict", help="output dict filename prefix")
parser.add_argument("-q", type=str, required=True,
                    help="input file of digits right of the decimal point (in Digits/)")
parser.add_argument("-s", type=int, default=1, help="starting dict index")
parser.add_argument("-e", type=int, default=1, help="ending dict index")
parser.add_argument("-b", type=int, default=2, help="base")
parser.add_argument("-pd", type=str, default="1",
                    help="period of continued fraction, e.g. '2 1 1'")
parser.add_argument("-p", action="store_true", help="write intermediate tables to Results/")
args = parser.parse_args()

# Extract arguments
fileName = args.f
inputFile = args.q
base = args.b
pd = [int(n) for n in args.pd.split()]
pdSz = len(pd)
dictStart = max(1, args.s)
dictEnd = max(dictStart, args.e)
printTable = args.p

# Read input digits
quad_path = os.path.join("Digits", inputFile)
try:
    with open(quad_path, "r") as quadFile:
        quadDigits = quadFile.read().replace(",", "").replace(" ", "").strip()
except FileNotFoundError:
    print(f'Input digit file "{quad_path}" not found.')
    exit(1)
except IOError as e:
    print(f'An error occurred: {e}')
    exit(1)
if "." in quadDigits:
    print("Only include digits right of decimal point in input file")
    exit(1)

# Check we have enough digits
if len(quadDigits) < dictEnd:
    print(f'Input file has only {len(quadDigits)} digits; need at least {dictEnd}.')
    exit(1)

# Initialize tables
powA = [1]
seqA = [1, pd[0]]
repA = ["0"]
repPA = ["1"]

# Table generators

def gen_power(start, end):                # b^i table
    for i in range(start, end + 1):
        powA.append(powA[i-1] * base)


def gen_sequence(start, end):             # denominators q_i
    if start < 2:
        start = 2
    for i in range(start, end + 1):
        seqA.append(pd[(i-1) % pdSz] * seqA[i-1] + seqA[i-2])


def gen_rep(start, end):                  # reference reps
    if start < 1:
        start = 1
    for i in range(start, end + 1):
        res = i
        leading = True
        repA.insert(i, "")
        for j in reversed(range(len(seqA))):
            seqVal = seqA[j]
            pdIdx = j % pdSz
            if seqVal > res:
                if not leading:
                    repA[i] += "0"
            else:
                for k in reversed(range(pd[pdIdx] + 1)):
                    if seqVal * k <= res:
                        res -= seqVal * k
                        repA[i] += str(k)
                        leading = False
                        break
            if seqVal == 1:
                break


def gen_repP(start, end):                 # Ostrowski reps for b^i
    for i in range(start, end + 1):
        res = powA[i]
        leading = True
        repPA.insert(i, "")
        for j in reversed(range(len(seqA))):
            seqVal = seqA[j]
            pdIdx = j % pdSz
            if seqVal > res:
                if not leading:
                    repPA[i] += "0"
            else:
                for k in reversed(range(pd[pdIdx] + 1)):
                    if seqVal * k <= res:
                        res -= seqVal * k
                        repPA[i] += str(k)
                        leading = False
                        break
            if seqVal == 1:
                break


def create_entry(index):
    # ensure power table covers index
    if index > len(powA)-1:
        gen_power(len(powA), index)
    # ensure seqA covers until it's >= powA[index]
    while powA[index] > seqA[-1]:
        gen_sequence(len(seqA), len(seqA)*2)
    # ensure repA and repPA ready
    if index > len(repA)-1:
        gen_rep(len(repA), index)
    if index > len(repPA)-1:
        gen_repP(len(repPA), index)
    return repPA[index], len(repPA[index])


def print_array(path, arr):
    with open(path, "w") as f:
        for i, v in enumerate(arr):
            f.write(f"{i} {v}\n")


def print_all(prefix):
    print_array(f"Results/{prefix}_Pow.txt", powA)
    print_array(f"Results/{prefix}_Seq.txt", seqA)
    print_array(f"Results/{prefix}_Rep.txt", repA)
    print_array(f"Results/{prefix}_RepP.txt", repPA)


def main():
    start_time = time.time()
    alphMax = max(pd)
    for i in range(dictStart, dictEnd + 1):
        out_path = f"{fileName}{i}.txt"
        with open(out_path, "w") as f:
            f.write(f"{i+1} {alphMax + 1}\n")
            f.write("0 1 0\n")
            for k in range(i):
                digits, length = create_entry(k)
                f.write(f"{quadDigits[k]} {length} {' '.join(digits)}\n")
    elapsed = time.time() - start_time
    print("Elapsed time:", elapsed, "seconds")
    if printTable:
        print_all(fileName)

if __name__ == "__main__":
    main()
