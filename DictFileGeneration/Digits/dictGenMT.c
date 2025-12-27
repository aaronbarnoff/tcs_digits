// Usage: ./dictGenMT <digitFile> <dictSize> <base> <"c.f period"> <numThreads>
// Compile: gcc -O3 dictGenMT.c -o dictGenMT -lgmp -lm -lpthread
// eg ./dictGenMT 081_b2_1M.txt 100000 2 "8 1" 32

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <gmp.h>
#include <pthread.h>
#include <time.h>
#include <assert.h>
#include <ctype.h>

#define CACHE_LINE 64
#define DICTNAME "tmpDict"

// Dynamic array for mpz_t values
typedef struct {
    mpz_t* data;
    size_t size;
    size_t capacity;
} BigArr;

// One output line's data, padded to a cache line
typedef struct {
    int digit;
    int length;
    char* str;
    char _pad[CACHE_LINE - sizeof(int)*2 - sizeof(char*)];
} OutputLine;

// Arguments for each thread
typedef struct {
    BigArr* seqArr;
    BigArr* powArr;
    int* digArr;
    int* pd;
    int pdSz;
    int dictSize;
    OutputLine* outArr;
    int start;
    int end;
} ThreadArgs;

// Initialize BigArr
void bigArrInit(BigArr* arr, size_t cap) {
    arr->capacity = cap;
    arr->size = 0;
    arr->data = malloc(cap * sizeof(mpz_t));
    assert(arr->data);
    for (size_t i = 0; i < cap; i++) 
        mpz_init(arr->data[i]);
}

// Free BigArr
void bigArrFree(BigArr* arr) {
    for (size_t i = 0; i < arr->capacity; i++) 
        mpz_clear(arr->data[i]);
    free(arr->data);
    arr->size = arr->capacity = 0;
}

// Append a value
void bigArrAppend(BigArr* arr, const mpz_t val) {
    if (arr->size >= arr->capacity) 
    {
        size_t ncap = arr->capacity * 2;
        arr->data = realloc(arr->data, ncap * sizeof(mpz_t));
        assert(arr->data);
        for (size_t i = arr->capacity; i < ncap; i++) 
            mpz_init(arr->data[i]);
        arr->capacity = ncap;
    }
    mpz_set(arr->data[arr->size++], val);
}

// Build continued-fraction q-sequence indices [start..end]
void genSequence(BigArr* seqArr, int* pd, int pdSz, int start, int end) {
    mpz_t c, d, e;
    mpz_inits(c, d, e, NULL);
    for (int i = start; i <= end; i++) 
    {
        mpz_set_ui(c, pd[(i-1) % pdSz]);
        mpz_mul(d, c, seqArr->data[i-1]);
        mpz_add(e, d, seqArr->data[i-2]);
        bigArrAppend(seqArr, e);
    }
    mpz_clears(c, d, e, NULL);
}

// Generate Ostrowski representation string in pre-allocated scratch buffer
void genOstRep(
    const BigArr* seqArr,
    const mpz_t powDigit,
    int* pd, int pdSz,
    mpz_t rem, mpz_t c, mpz_t d, mpz_t one,
    char* scratch, int bufCap,
    int* outLen
) {
    mpz_set(rem, powDigit);
    int len = 0;
    int leadingZero = 1;
    int n = (int)seqArr->size;
    for (int j = n - 1; j >= 0; j--) 
    {
        if (mpz_cmp(seqArr->data[j], rem) > 0) 
        {
            if (!leadingZero) 
            {
                scratch[len++] = '0'; scratch[len++] = ' ';
            }
        } 
        else 
        {
            int idx = j % pdSz;
            for (int k = pd[idx]; k >= 1; k--) 
            {
                mpz_set_ui(c, k);
                mpz_mul(d, seqArr->data[j], c);
                if (mpz_cmp(d, rem) <= 0) 
                {
                    mpz_sub(rem, rem, d);
                    scratch[len++] = '0' + k;
                    scratch[len++] = ' ';
                    leadingZero = 0;
                    break;
                }
            }
        }
        if (mpz_cmp(seqArr->data[j], one) == 0) 
            break;
    }
    if (len > 0) 
        scratch[len-1] = '\0'; 
    else 
        scratch[0] = '\0';
    *outLen = len/2;
}

// Thread function
void* runThread(void* arg) {
    ThreadArgs* A = arg;
    // prepare mpz temporaries
    mpz_t rem, c, d, one;
    mpz_init(rem); mpz_init(c); mpz_init(d); mpz_init_set_ui(one, 1);
    int bufCap = A->seqArr->size * 2 + 2;
    char* scratch = malloc(bufCap);
    assert(scratch);

    for (int i = A->start; i <= A->end; i++) 
    {
        genOstRep(
            A->seqArr, A->powArr->data[i-1],
            A->pd, A->pdSz,
            rem, c, d, one,
            scratch, bufCap,
            &A->outArr[i].length
        );
        A->outArr[i].digit = A->digArr[i];
        // duplicate string into output line
        A->outArr[i].str = strdup(scratch);
    }

    free(scratch);
    mpz_clear(rem); mpz_clear(c); mpz_clear(d); mpz_clear(one);
    free(A);
    return NULL;
}

int main(int argc, char** argv) {
    if (argc != 6) 
    {
        fprintf(stderr, "Usage: %s digitFile dictSize base \"c.f period\" numThreads\n", argv[0]);
        return 1;
    }
    char* digFile = argv[1];
    int dictSize = atoi(argv[2]);
    int base = atoi(argv[3]);
    char* period = argv[4];
    int numThreads = atoi(argv[5]);

    // parse period
    int pdSz = 0; for (char* p = period; *p; p++) if (*p!=' ') pdSz++;
    int* pd = malloc(pdSz * sizeof(int));
    pdSz = 0;
    for (char* p = period; *p; p++) 
        if (*p!=' ') 
            pd[pdSz++] = *p - '0';

    printf("digitFile:%s, dictSize:%d, base:%d, numThreads:%d.\n", digFile, dictSize, base, numThreads);
    printf("period:");
    for (int i = 0; i < pdSz; i++)
    {
        printf("%d ", pd[i]);
    }
    printf("\n");
    // read digits
    int* digArr = malloc((dictSize+1)*sizeof(int));
    if (!digArr) { perror("malloc digArr"); exit(1); }
    digArr[0] = 0;       

    FILE* fpDig = fopen(digFile, "r");
    if (!fpDig) { perror("fopen digitFile"); exit(1); }

    for (int i = 1; i <= dictSize; ) 
    {
        int c = fgetc(fpDig);
        if (c == EOF) 
        {
            fprintf(stderr, "Unexpected EOF reading digit %d\n", i);
            exit(1);
        }
        if (c == '0' || c == '1') 
        {
            digArr[i++] = c - '0';
        }
    }
fclose(fpDig);

    // build CF sequence
    BigArr seqArr; bigArrInit(&seqArr, 8);
    mpz_t bound, tmp;
    mpz_init(bound); mpz_ui_pow_ui(bound, base, dictSize);
    mpz_init_set_ui(tmp,1);
    bigArrAppend(&seqArr, tmp);
    mpz_set_ui(tmp, pd[0]); bigArrAppend(&seqArr, tmp);
    while (mpz_cmp(seqArr.data[seqArr.size-1], bound)<0)
        genSequence(&seqArr, pd, pdSz, seqArr.size, seqArr.size+64);
    mpz_clear(tmp); mpz_clear(bound);

    // precompute powers
    BigArr powArr; bigArrInit(&powArr, dictSize+1);
    mpz_init_set_ui(tmp,1);
    mpz_t bp; mpz_init_set_ui(bp, base);
    for(int i=0;i<=dictSize;i++) 
    {
        bigArrAppend(&powArr, tmp);
        mpz_mul(tmp, tmp, bp);
    }
    mpz_clear(tmp); mpz_clear(bp);

    // prepare output array
    OutputLine* outArr = calloc(dictSize+1, sizeof(OutputLine));

    // launch threads
    pthread_t* th = malloc(numThreads*sizeof(pthread_t));
    int chunk=(dictSize+numThreads-1)/numThreads;
    int created = 0;
    struct timespec t0,t1; clock_gettime(CLOCK_MONOTONIC,&t0);
    for(int t=0;t<numThreads;t++)
    {
        ThreadArgs* A=malloc(sizeof(ThreadArgs));
        int s=1+t*chunk, e=s+chunk-1; 
        if (s > dictSize)
        {
            free(A);
            break;
        }
        if(e>dictSize)
            e=dictSize;
        *A=(ThreadArgs){&seqArr,&powArr,digArr,pd,pdSz,dictSize,outArr,s,e};
        pthread_create(&th[created],NULL,runThread,A);
        created++;
    }
    for(int t=0;t<created;t++) 
        pthread_join(th[t],NULL);
    clock_gettime(CLOCK_MONOTONIC,&t1);
    double elapsed=(t1.tv_sec-t0.tv_sec)+(t1.tv_nsec-t0.tv_nsec)/1e9;

    // bulk write
    char fname[64]; 
    snprintf(fname,64,"%s%d.txt",DICTNAME,dictSize);
    FILE* fo=fopen(fname,"w"); 
    if(!fo){
        perror("fopen out");
        return 1;
    }
    int maxPd=0; 
    for(int i=0;i<pdSz;i++) 
        if(pd[i]>maxPd)
            maxPd=pd[i];
    fprintf(fo,"%d %d\n",dictSize+1,maxPd+1);
    fprintf(fo,"0 1 0\n");
    size_t tot=0;
    for(int i=1;i<=dictSize;i++) 
        tot+=snprintf(NULL,0,"%d %d %s\n",outArr[i].digit,outArr[i].length,outArr[i].str);
    char* buf=malloc(tot+1); char* p=buf;
    for(int i=1;i<=dictSize;i++) 
        p+=sprintf(p,"%d %d %s\n",outArr[i].digit,outArr[i].length,outArr[i].str);
    fwrite(buf,1,p-buf,fo);
    fclose(fo); free(buf);

    printf("Time taken: %.6f seconds\n",elapsed);

    // cleanup
    for(int i=1;i<=dictSize;i++) 
        free(outArr[i].str);
    free(outArr);
    bigArrFree(&seqArr);
    bigArrFree(&powArr);
    free(digArr);
    free(pd);
    free(th);
    return 0;
}
