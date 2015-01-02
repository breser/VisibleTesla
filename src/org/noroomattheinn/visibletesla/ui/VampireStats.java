/*
 * VampireStats.java - Copyright(c) 2014 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Apr 05, 2014
 */
package org.noroomattheinn.visibletesla.ui;

import org.noroomattheinn.visibletesla.ui.App;
import org.noroomattheinn.visibletesla.vehicle.VTVehicle;
import org.noroomattheinn.visibletesla.data.RestCycle;
import com.google.common.collect.Range;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import org.noroomattheinn.timeseries.Row;
import org.noroomattheinn.utils.Utils;
import org.noroomattheinn.visibletesla.data.VTData;
import org.noroomattheinn.visibletesla.dialogs.DateRangeDialog;
import org.noroomattheinn.visibletesla.dialogs.VampireLossResults;

/**
 * VampireStats: Collect and display statistics about vampire loss.
 * 
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class VampireStats {
    
    
/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/
    
    private final App ac;
    private boolean         useMiles;
    
    
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/
    
    public VampireStats(App ac) {
        this.ac = ac;
    }
        
    public void showStats() {
        useMiles = VTVehicle.get().unitType() == Utils.UnitType.Imperial;
        Range<Long> exportPeriod = getExportPeriod();
        if (exportPeriod == null) { return; }
        
        final List<RestCycle> rests = VTData.get().getRestCycles(exportPeriod);

        displayResults(rests);
    }
    
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE - Methods to accumulate Rests
 * 
 *----------------------------------------------------------------------------*/
    
    
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE - UI Related methods: Select the export period, display the results
 * 
 *----------------------------------------------------------------------------*/
    
    private void displayResults(List<RestCycle> rests) {
        // Compute some stats and generate detail output
        long totalRestTime = 0;
        double totalLoss = 0;
        for (RestCycle r : rests) {
            totalRestTime += r.endTime - r.startTime;
            totalLoss += r.startRange - r.endRange;
        }
                
        VampireLossResults.show(ac.stage, rests, useMiles ? "mi" : "km", totalLoss/hours(totalRestTime));
    }
    
    
    private Range<Long> getExportPeriod() {
        NavigableMap<Long,Row> rows = VTData.get().statsCollector.getAllLoadedRows();
        long timestamp = rows.firstKey(); 
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(timestamp);
        
        timestamp = rows.lastKey(); 
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(timestamp);
        
        Range<Long> exportPeriod = DateRangeDialog.getExportPeriod(ac.stage, start, end);
        return exportPeriod;
    }
    
    private Map<String,Object> genProps() {
        NavigableMap<Long,Row> rows = VTData.get().statsCollector.getAllLoadedRows();
        
        Map<String,Object> props = new HashMap<>();
        long timestamp = rows.firstKey(); 
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(timestamp);
        props.put("HIGHLIGHT_START", start);
        
        timestamp = rows.lastKey(); 
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(timestamp);
        props.put("HIGHLIGHT_END", end);
        return props;
    }
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE - Utility Methods
 * 
 *----------------------------------------------------------------------------*/
    
    private double hours(long millis) {return ((double)(millis))/(60 * 60 * 1000); }
    

}