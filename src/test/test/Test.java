public class Test {


    public static void main(String[] args) {
        final int b = Example.lol();
        Thread.currentThread();

    }
}

class Example {
    static int a = 5;
    public static int lol(){
        return a;
    }
}