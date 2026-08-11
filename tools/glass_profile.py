#!/usr/bin/env python3
"""Mede o perfil optico de uma superficie de vidro num screenshot.

Este script e o instrumento com que a optica da barra flutuante foi corrigida, e
esta versionado porque a correcao nao teria sido possivel sem ele. Olhar para dois
screenshots lado a lado nao distingue "a borda esta fraca" de "a borda de baixo nao
existe", e essa diferenca era a causa raiz. Medir distingue.

O metodo: varrer uma linha vertical pelo meio da capsula, num x onde nao haja icone,
e imprimir a luminancia por pixel. Isso revela de uma vez a borda de cima, a queda
para dentro, o corpo, e a borda de baixo. Depois varrer as duas bordas ao longo do
comprimento para ver se elas sao continuas ou se apagam em alguma regiao.

Uso:
    python tools/glass_profile.py <imagem> --column X --from Y0 --to Y1
    python tools/glass_profile.py <imagem> --edge X0 X1 --band Y0 Y1

Exemplo (a medicao que diagnosticou o "bump de plastico"):
    python tools/glass_profile.py demo/liquid-glass/navbar-before-optics-fix.jpg \
        --column 1000 --from 2820 --to 3060
    -> borda de cima com pico 120, borda de baixo 30, corpo em curva 30->46->16

    python tools/glass_profile.py demo/liquid-glass/navbar-reference-target.png \
        --column 470 --from 1676 --to 1812
    -> borda de cima 225, borda de baixo 183, corpo plano ~36

Requer Pillow (pip install pillow).
"""

import argparse
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow ausente. Instale com: pip install pillow")

# Coeficientes de luminancia relativa Rec.709, os mesmos que o shader usa em
# LiquidLens para o termo de saturacao. Manter iguais e o que torna as medidas
# comparaveis com os numeros do shader.
LUMA = (0.2126, 0.7152, 0.0722)


def luminance(pixel):
    return sum(c * w for c, w in zip(pixel[:3], LUMA))


def column_profile(image, x, y0, y1, step):
    """Perfil vertical: mostra as duas bordas, a queda para dentro e o corpo."""
    print(f"# perfil vertical em x={x}, y de {y0} a {y1}")
    print(f"{'y':>6} {'luma':>7}  rgb")
    for y in range(y0, y1, step):
        p = image.getpixel((x, y))
        print(f"{y:>6} {luminance(p):>7.1f}  {p}")


def edge_profile(image, x0, x1, y0, y1, step):
    """Pico de luminancia por coluna dentro de uma faixa horizontal.

    E assim que se prova que uma borda e continua. Uma borda de vidro real mantem
    um pico alto ao longo de todo o contorno; a borda que estava errada mantinha
    30/255 de ponta a ponta, uniformemente apagada.
    """
    print(f"# pico por coluna, x de {x0} a {x1}, faixa y {y0}..{y1}")
    print(f"{'x':>6} {'luma':>7} {'y':>6}  rgb")
    peaks = []
    for x in range(x0, x1, step):
        best = max(
            ((luminance(image.getpixel((x, y))), y, image.getpixel((x, y)))
             for y in range(y0, y1)),
            key=lambda t: t[0],
        )
        peaks.append(best[0])
        print(f"{x:>6} {best[0]:>7.1f} {best[1]:>6}  {best[2]}")
    if peaks:
        print(f"# min={min(peaks):.1f} max={max(peaks):.1f} "
              f"media={sum(peaks)/len(peaks):.1f}")
        print("# uma borda que varia pouco e uniforme demais para vidro; uma que")
        print("# fica abaixo de ~60 numa regiao inteira nao esta la.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image")
    ap.add_argument("--column", type=int, help="x da varredura vertical")
    ap.add_argument("--from", dest="y0", type=int, help="y inicial")
    ap.add_argument("--to", dest="y1", type=int, help="y final")
    ap.add_argument("--edge", nargs=2, type=int, metavar=("X0", "X1"),
                    help="varredura horizontal de borda")
    ap.add_argument("--band", nargs=2, type=int, metavar=("Y0", "Y1"),
                    help="faixa y onde procurar o pico da borda")
    ap.add_argument("--step", type=int, default=1, help="passo da amostragem")
    args = ap.parse_args()

    image = Image.open(args.image).convert("RGB")
    print(f"# {args.image} {image.size[0]}x{image.size[1]}")
    print("# Atencao: se o screenshot foi reescalado, converta as profundidades")
    print("# para pixels do dispositivo antes de comparar com o shader.")

    if args.column is not None:
        if args.y0 is None or args.y1 is None:
            sys.exit("--column exige --from e --to")
        column_profile(image, args.column, args.y0, args.y1, args.step)
    elif args.edge:
        if not args.band:
            sys.exit("--edge exige --band")
        edge_profile(image, args.edge[0], args.edge[1],
                     args.band[0], args.band[1], args.step)
    else:
        sys.exit("escolha --column ou --edge")


if __name__ == "__main__":
    main()
