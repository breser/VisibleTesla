/*
 * MessageTemplate.java - Copyright(c) 2014 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: May 31, 2014
 */
package org.noroomattheinn.visibletesla;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.noroomattheinn.utils.GeoUtils;
import org.noroomattheinn.utils.Utils;

/**
 * MessageTemplate
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class MessageTemplate {
    
/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/
    
    // The overall message template is represented by a list of MsgComponents
    private List<MsgComponent> components;

/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/

    public MessageTemplate(String format) {
        components = new ArrayList<>();
        if (format == null) {
            return;
        }
        parse(format);
    }

    public String getMessage(AppContext ac, Map<String,String> contextSpecific) {
        StringBuilder sb = new StringBuilder();
        for (MsgComponent mc : components) {
            sb.append(mc.asString(ac, contextSpecific));
        }
        return sb.toString();
    }

/*------------------------------------------------------------------------------
 *
 * PRIVATE Methods that parse a template string
 * 
 *----------------------------------------------------------------------------*/
    
    private void parse(String input) {
        while (input != null) {
            input = next(input);
        }
    }
    
    private String next(String input) {
        if (input == null) return null;
        if (input.isEmpty()) return null;
        int length = input.length();
        for (int i = 0; i < length; i++) {
            if (input.charAt(i) == '{') {
                if (i+1 != length && input.charAt(i+1) == '{') {
                    // Matched {{
                    if (i != 0) {
                        components.add(new MsgComponent.StrComponent(input.substring(0, i)));
                        return input.substring(i);
                    } else {
                        // Search for matching }}
                        for (int j = i+2; j < length; j++) {
                            if (input.charAt(j) == '}') {
                                if (j+1 != length && input.charAt(j+1) == '}') {
                                    // Founding matching }}
                                    components.add(new MsgComponent.VarComponent(input.substring(i+2, j)));
                                    return input.substring(j+2);
                                }
                            }
                        }
                    }
                }
            }
        }
        components.add(new MsgComponent.StrComponent(input));
        return null;
    }
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE class that implements an individual component of a message
 * 
 *----------------------------------------------------------------------------*/
    
    private static abstract class MsgComponent {
        abstract String asString(AppContext ac, Map<String,String> contextSpecific);

        // A String component is very simple - it's just a literal String
        static class StrComponent extends MsgComponent {
            public String string;
            
            StrComponent(String s) { this.string = s; }
            
            @Override String asString(AppContext ac, Map<String,String> cs) { return string; }
        }

        // A Variable component represents a formatted reading from the car
        static class VarComponent extends MsgComponent {
            public String varName;

            VarComponent(String v) { this.varName = v; }

            @Override String asString(AppContext ac, Map<String,String> contextSpecific) {
                String val;
                switch (varName) {
                    case "SPEED": val = String.format(
                            "%3.1f", ac.lastKnownSnapshotState.get().speed);
                        break;
                    case "SOC": val = String.valueOf(ac.lastKnownSnapshotState.get().soc);
                        break;
                    case "IDEAL": val = String.format(
                            "%3.1f", ac.inProperUnits(ac.lastKnownChargeState.get().idealRange));
                        break;
                    case "RATED": val = String.format(
                            "%3.1f", ac.inProperUnits(ac.lastKnownChargeState.get().range));
                        break;
                    case "ESTIMATED": val = String.format(
                            "%3.1f", ac.inProperUnits(ac.lastKnownChargeState.get().estimatedRange));
                        break;
                    case "CHARGE_STATE": val = ac.lastKnownChargeState.get().chargingState.name();
                        break;
                    case "D_UNITS": val = ac.unitType() == Utils.UnitType.Imperial ? "mi" : "km";
                        break;
                    case "S_UNITS": val = ac.unitType() == Utils.UnitType.Imperial ? "mph" : "km/h";
                        break;
                    case "DATE":
                        val = String.format("%1$tY-%1$tm-%1$td", new Date());
                        break;
                    case "TIME":
                        val = String.format("%1$tH:%1$tM:%1$tS", new Date());
                        break;
                    case "LOC":
                        String lat = String.valueOf(ac.lastKnownSnapshotState.get().estLat);
                        String lng = String.valueOf(ac.lastKnownSnapshotState.get().estLng);
                        val = GeoUtils.getAddrForLatLong(lat, lng);
                        if (val == null || val.isEmpty()) {
                            val = String.format("(%s, %s)", lat, lng);
                        }
                        break;
                    case "I_STATE":
                        val = ac.inactivityStateAsString();
                        break;
                    case "I_MODE":
                        val = ac.inactivityModeAsString();
                        break;
                    case "P_CURRENT":
                        val = String.valueOf(
                                ac.lastKnownChargeState.get().chargerPilotCurrent);
                        break;
                    case "TIME_TO_FULL":
                        val = ChargeController.getDurationString(
                            ac.lastKnownChargeState.get().timeToFullCharge); 
                        break;
                    case "C_RATE":
                        val = String.format(
                            "%.1f", ac.inProperUnits(ac.lastKnownChargeState.get().chargeRate));
                        break;
                    case "C_AMP":
                        val = String.format(
                            "%.1f", ac.inProperUnits(ac.lastKnownChargeState.get().batteryCurrent));
                        break;
                    case "C_VLT":
                        val = String.valueOf(ac.lastKnownChargeState.get().chargerVoltage);
                        break;
                    case "C_PWR":
                        val = String.valueOf(ac.lastKnownChargeState.get().chargerPower);
                        break;
                    default:
                        val = (contextSpecific == null) ? null : contextSpecific.get(varName);
                        if (val == null) val = "Unknown variable: " + varName;
                        break;
                }
                return val;
            }
        }
    }
}
