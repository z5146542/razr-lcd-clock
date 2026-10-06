package dev.lcdclock;

/** The compact "LCD Clock time" home screen widget (3x1): just the face's time row. See ClockWidget. */
public class TimeWidget extends ClockWidget {
    @Override
    boolean timeOnly() {
        return true;
    }
}
