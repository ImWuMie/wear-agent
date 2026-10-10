# JLatexMath 0.2.0 registers textual macros (dfrac, tfrac, operatorname and environments)
# through Class.forName and getDeclaredMethod. Keep only this reflection contract, not the engine.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}
