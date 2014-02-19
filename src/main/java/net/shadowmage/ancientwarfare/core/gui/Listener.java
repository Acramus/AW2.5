package net.shadowmage.ancientwarfare.core.gui;

import net.shadowmage.ancientwarfare.core.gui.GuiContainerBase.ActivationEvent;
import net.shadowmage.ancientwarfare.core.gui.elements.GuiElement;

public abstract class Listener
{
public static final int KEY_UP = 1;
public static final int KEY_DOWN = 2;
public static final int MOUSE_UP = 4;
public static final int MOUSE_DOWN = 8;
public static final int MOUSE_WHEEL = 16;
public static final int MOUSE_MOVED = 32;

public static final int KEY_TYPES = 0 + 1;
public static final int MOUSE_TYPES = 2 + 4 + 8 + 16;
public static final int ALL_EVENTS = 0xffffffff;

public final int type;
private GuiElement element;

public GuiElement getElement()
  {
  return element;
  }

public Listener(int type)
  {
  this.type = type;
  }

public void setElement(GuiElement element)
  {
  this.element = element;
  }

public abstract boolean onEvent(ActivationEvent evt);

}
