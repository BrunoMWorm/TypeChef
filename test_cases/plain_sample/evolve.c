int bar (int a) {
    if (a < 0) {
        // Change: edit inside node
        // return a + 1;
        return a + 4;
    }
    return a;
}

int baz (int a, int b) {
    while(a > 0) {
        b = b + b;
	    a--;
    }
    // Change: new node
    if (b < 0) {
        return 0;
    }
    return b; 
}

int ban (int a, int b) {
    // Edit: deleted node
    // if (a < 0) {
    //     return 0;
    // }
    return a * b;
}

int boink (int a, int b) {
    // Edit: swap order of declarations
    int y = b;
    int x = a;
    return x * y;
}

int funcao_renomeada (int a, int b) {
    int fatorBar = bar(b);
    return (a * 2 + fatorBar);
}

int eita (int a, int b) {
   int fator = b * 3;
   return (a * fator);
}

int funcao_adicionada (int a, int b) {
   int x = a*a + 2*a*b + b*b;
   return x;
}

int main() {
    return 0;
}
