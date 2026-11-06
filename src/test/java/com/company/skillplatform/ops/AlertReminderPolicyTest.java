package com.company.skillplatform.ops;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AlertReminderPolicyTest {
 @Test void reachesFourHourCapWithoutOverflow(){
  int[] expected={5,10,20,40,80,160,240,240,240};int total=0;int[] cumulative={5,15,35,75,155,315,555,795,1035};
  for(int i=0;i<expected.length;i++){int delay=AlertReminderPolicy.delayMinutes(5,2,240,i);assertEquals(expected[i],delay);total+=delay;assertEquals(cumulative[i],total);}
 }
 @Test void invalidPolicyRejected(){assertThrows(IllegalArgumentException.class,()->AlertReminderPolicy.delayMinutes(0,2,240,0));}
}
