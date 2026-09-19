public class Test {


    public static void main(String[] args) {
        final int b = Example.lol();
        Thread.currentThread();

        String lol = "true";
        var x = lol.equals("true");
        throw new IllegalArgumentException("test" + b + " " + Thread.currentThread().getName() + "here should be true:"+ x);
    }
}

class Example {
    static int a = 5;
    static int testValuedontinit = 15;

    static {
        String lol = "dont add this";
        String butThis = "this should be added";
    }
    public static int lol(){
        return a;
    }

}