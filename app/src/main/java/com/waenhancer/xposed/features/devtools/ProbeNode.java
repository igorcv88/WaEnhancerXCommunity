package com.waenhancer.xposed.features.devtools;

import java.util.List;

/**
 * A árvore de views vista sem depender de android.view.View.
 *
 * <p>Existe para que o hit-test — a parte mais fácil de errar do inspector — seja testável na
 * JVM, já que o projeto não tem Robolectric. O adaptador que converte View em ProbeNode vive na
 * camada Android e é deliberadamente burro.</p>
 *
 * <p>As bounds são as <b>recortadas</b> (o que getGlobalVisibleRect devolve), nunca as nominais.
 * É essa escolha que faz uma linha rolada para fora da lista não ser acertada.</p>
 */
public interface ProbeNode {

    int left();

    int top();

    int right();

    int bottom();

    boolean visible();

    float alpha();

    List<ProbeNode> children();

    ProbeNode parent();

    /** Nome do recurso, ou null quando a view não tem id. */
    String entryName();

    String resourcePackage();

    int id();

    String className();

    String contentDescription();
}
