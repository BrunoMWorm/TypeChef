int bar (int a) {
    if (a < 0) {
        return a + 1;
    }
    return a;
}

int baz (int a, int b) {
    while(a > 0) {
        b = b + b;
	    a--;
    }
    return b; 
}

int ban (int a, int b) {
    if (a < 0) {
        return 0;
    }
    return a * b;
}

int boink (int a, int b) {
    int x = a;
    int y = b;
    return x * y;
}

int funcao_a_ser_renomeada (int a, int b) {
    int fatorBar = bar(b);
    return (a * 2 + fatorBar);
}

int funcao_que_sera_removida (int a, int b) {
   int x = a + b;
   int y = a - b;

   return x * y;
}

int eita (int a, int b) {
   // Comentários não devem afetar
   int fator = b * 3;
   
   return (a * fator);
}

int main() {
    return 0;
}