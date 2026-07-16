package cc.nkbr.lanzouplus;

/** Linearizes pause/cancel against transfer completion. */
final class TransferTerminal {
  private volatile int stop;
  private boolean terminal;
  synchronized boolean request(int mode){if(terminal)return false;if(stop==0||mode==2)stop=mode;return true;}
  synchronized int claim(){if(terminal)return -1;terminal=true;return stop;}
  int stopMode(){return stop;}
}
