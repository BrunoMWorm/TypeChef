int eita_original (int a, int b) {
   int fator = b * 3;
   #ifdef ADICIONAL_FATOR
   fator += 1;
   #endif
   return (a * fator);
}

int eita_a_ser_modificada (int a, int b) {
   int fator = b * 4;
   #ifdef ADICIONAL_FATOR
   fator += 1;  
   #endif
   return (a * fator);
}

int foo_original
#ifdef A
(int a) {
    #ifdef B
    return a * 2;
    #else
    int x = a * 3 + 1;
    #endif
#else
(int a, int b) {
    return (a
    #ifdef B
        +
    #else
        * 2 +
    #endif
    b);
#endif
}

int foo_modificada_dentro_else
#ifdef A
(int a) {
    #ifdef B
    return a * 2;
    #else
    int x = a * 3 + 1;
    #endif
#else
(int a, int b) {
    return (a
    #ifdef B
        +
    #else
        * 3 +
    #endif
    b);
#endif
}

int foo_modificada_condicao_presenca
#ifdef A
(int a) {
    #ifdef B
    return a * 2;
    #else
    int x = a * 3 + 1;
    #endif
#else
(int a, int b) {
    return (a
    #ifdef B
        +
    #else
        * 2 +
    #endif
    b);
#endif
}