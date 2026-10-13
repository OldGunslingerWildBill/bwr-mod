package dev.bwr.mod.panel.model;

/** A panel model file that does not match the exporter's format or lies outside its footprint. */
public final class ModelFormatException extends RuntimeException {
    public ModelFormatException(String message) {
        super(message);
    }
}
