package io.github.brooswitminecraft.dynamicpopulation.propagation;

/** A population cell: horizontal cell indices and the vertical layer index (0 at the world's min build height). */
public record CellKey(int x, int layer, int z) {
    public CellKey offset(int dx, int dLayer, int dz) {
        return new CellKey(x + dx, layer + dLayer, z + dz);
    }
}
