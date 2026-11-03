package comp3011;


/** A block of decoded audio on its way from the decoder to the JavaFX thread
 * Immutable
 */
public record Audio(long timestampUs, byte[] samples, int epoch) {

}
