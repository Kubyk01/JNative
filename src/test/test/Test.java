public class Test {


    public static void main(String[] args) {
        System.err.println("[dbg] System.out = " + System.out);    }
}

class Example {
    static int a = 5;
    static int testValuedontinit = 15;

    static {
        String lol = "dont add this";
    }
    public static int lol(){
        return a;
    }
}