package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.eccs.AdsControllerBlockEntity;
import dev.bwr.mod.signal.Schema;

/**
 * The Automatic Depressurisation System controller: what a panel can read of it, and the three inputs the control
 * room's ADS switches are wired to. The controller still opens its valves only when the player's programs or hands tell
 * it to; these inputs are the hardware of that, not logic.
 *
 * <ul>
 *   <li>{@code ads_allow}: clear (0), the bank is not held open whatever else asks; set (1, the default), as always.
 *       The OPEN/AUTO/CLOSE switch of the relief valves is what players wire here: AUTO only means "ADS may open
 *       this valve", CLOSE means it may not.</li>
 *   <li>{@code ads_activate_a} and {@code ads_activate_b}: the two manual activation switches ("push both to
 *       activate"). Bringing both to 1 together, while allowed, opens the bank once, like a program's
 *       {@code start()}. Wire each to the position of a push-to-turn switch.</li>
 * </ul>
 */
final class AdsSignals {
    private AdsSignals() {}

    static final Schema<AdsControllerBlockEntity> CONTROLLER = new Schema<AdsControllerBlockEntity>()
            .bool("open", "Bank open requested", AdsControllerBlockEntity::isOpen)
            .bool("allowed", "Allowed to open", AdsControllerBlockEntity::isAllowed)
            .bool("activate_a", "Manual activation A", AdsControllerBlockEntity::isActivateA)
            .bool("activate_b", "Manual activation B", AdsControllerBlockEntity::isActivateB)
            .number("valves", "Relief valves available", "valves", 0, 64, AdsControllerBlockEntity::getValveCount)
            .number("valves_open", "Relief valves held open", "valves", 0, 64, AdsControllerBlockEntity::getHeldOpenCount)
            .number("nitrogen", "Nitrogen charge", "fraction", 0, 1, AdsControllerBlockEntity::getNitrogenCharge)
            .toggle("ads_allow", "Allow the bank to open", (be, v) -> {
                be.setAllowed(v >= 0.5);
                return true;
            })
            .toggle("ads_activate_a", "Manual activation A", (be, v) -> {
                be.setActivateA(v >= 0.5);
                return true;
            })
            .toggle("ads_activate_b", "Manual activation B", (be, v) -> {
                be.setActivateB(v >= 0.5);
                return true;
            });
}
