package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.presentation.WindowPlacement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowPlacementTest {
    void inside(double left,double top,double width,double height){
        var placement=WindowPlacement.fit(left,top,width,height);
        assertTrue(placement.x()>=left);assertTrue(placement.y()>=top);
        assertTrue(placement.x()+placement.width()<=left+width);assertTrue(placement.y()+placement.height()<=top+height);
        assertTrue(placement.minWidth()<=placement.width());assertTrue(placement.minHeight()<=placement.height());
    }
    @Test void fitsTypicalLaptopWorkArea(){inside(0,0,1366,728);var p=WindowPlacement.fit(0,0,1366,728);assertTrue(p.height()<728);assertTrue(p.y()>0);}
    @Test void respectsLogicalBoundsWithWindowsScaling(){inside(0,0,1092.8,582.4);inside(0,0,1280,680);}
    @Test void supportsSecondaryMonitorWithNegativeOrigin(){inside(-1920,80,1920,1000);}
    @Test void minimumNeverExceedsASmallWorkArea(){inside(0,30,640,400);}
    @Test void largeScreenUsesBoundedInitialSize(){var p=WindowPlacement.fit(0,0,3840,2100);assertEquals(1220,p.width());assertEquals(900,p.height());assertEquals((3840-1220)/2.0,p.x());}
    @Test void invalidBoundsAreRejected(){assertThrows(IllegalArgumentException.class,()->WindowPlacement.fit(0,0,0,700));assertThrows(IllegalArgumentException.class,()->WindowPlacement.fit(0,0,1000,Double.NaN));}
}
