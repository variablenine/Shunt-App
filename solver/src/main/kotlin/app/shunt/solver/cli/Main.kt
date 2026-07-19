package app.shunt.solver.cli

/**
 * CLI harness for the route solver. The real `solve` command lands in M1;
 * this entry point exists so `:solver` is runnable from a laptop from day one.
 */
fun main(args: Array<String>) {
    println("shunt solver CLI — solve command arrives in M1. Args: ${args.joinToString(" ")}")
}
