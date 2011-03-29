package com.orange.links.client;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style.Cursor;
import com.google.gwt.dom.client.Style.Position;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseMoveHandler;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.dom.client.MouseUpHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.HandlerManager;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.AbsolutePanel;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.Widget;
import com.orange.links.client.canvas.BackgroundCanvas;
import com.orange.links.client.canvas.DiagramCanvas;
import com.orange.links.client.connection.Connection;
import com.orange.links.client.connection.StraightArrowConnection;
import com.orange.links.client.connection.StraightConnection;
import com.orange.links.client.event.ChangeOnDiagramEvent;
import com.orange.links.client.event.ChangeOnDiagramEvent.HasChangeOnDiagramHandlers;
import com.orange.links.client.event.ChangeOnDiagramHandler;
import com.orange.links.client.event.TieLinkEvent;
import com.orange.links.client.event.TieLinkEvent.HasTieLinkHandlers;
import com.orange.links.client.event.TieLinkHandler;
import com.orange.links.client.event.UntieLinkEvent;
import com.orange.links.client.event.UntieLinkEvent.HasUntieLinkHandlers;
import com.orange.links.client.event.UntieLinkHandler;
import com.orange.links.client.utils.LinksClientBundle;
import com.orange.links.client.utils.MovablePoint;
import com.orange.links.client.utils.Point;
import com.orange.links.client.utils.Rectangle;

/**
 * Controller which manage all the diagram logic
 * @author Pierre Renaudin (pierre.renaudin.fr@gmail.com)
 *
 */
public class DiagramController implements HasTieLinkHandlers,HasUntieLinkHandlers,HasChangeOnDiagramHandlers{

	/**
	 * If the distance between the mouse and segment is under this number in pixels, then, 
	 * the mouse is considered over the segment
	 */
	public static int minDistanceToSegment = 10;
	
	/**
	 * Timer refresh duration, in milliseconds. It defers if the application is running in development mode
	 * or in the web mode
	 */
	public static int refreshRate = GWT.isScript() ? 25 : 50;

	private DiagramCanvas canvas;
	private BackgroundCanvas backgroundCanvas;
	private AbsolutePanel widgetPanel;
	private HandlerManager handlerManager;
	private Map<Widget,FunctionShape> shapeMap;
	private boolean showGrid;

	private Set<Connection> connectionSet;

	private Point mousePoint;
	private Point mouseOffsetPoint;

	// Drag Edition status
	private boolean inEditionDragMovablePoint = false;
	private boolean inEditionSelectableShapeToDrawConnection = false;
	private boolean inDragBuildArrow = false;
	private boolean inDragMovablePoint = false;

	private Point highlightPoint;
	private Connection highlightConnection;
	private MovablePoint movablePoint;

	private Widget startFunctionWidget;
	private Connection buildConnection;
	
	long nFrame = 0;
	long previousNFrame = 0;
	long previousTime = 0;
	long fps = 0;

	/**
	 * Initialize the controller diagram. Use this constructor to start your diagram. An code sample is :
	 * <br/><br/>
	 * <code>
	 * 		DiagramCanvas canvas = new MultiBrowserDiagramCanvas(400,400);<br/>
	 *		DiagramController controller = new DiagramController(canvas);<br/>
	 * </code>
	 * <br/>
	 * @param canvas the implementation of the canvas where connections and widgets will be drawn
	 */
	public DiagramController(final DiagramCanvas canvas){
		this.canvas = canvas;
		this.backgroundCanvas = new BackgroundCanvas(canvas.getWidth(), canvas.getHeight());

		// Init widget panel
		widgetPanel = new AbsolutePanel();
		widgetPanel.getElement().getStyle().setWidth(canvas.getWidth(), Unit.PX);
		widgetPanel.getElement().getStyle().setHeight(canvas.getHeight(), Unit.PX);
		widgetPanel.add(canvas.asWidget());

		mousePoint = new Point(0,0);
		mouseOffsetPoint = new Point(0,0);
		
		connectionSet = new HashSet<Connection>();
		shapeMap = new HashMap<Widget,FunctionShape>();
		handlerManager = new HandlerManager(canvas);
		LinksClientBundle.INSTANCE.css().ensureInjected();

		// ON MOUSE MOVE
		canvas.addDomHandler(new MouseMoveHandler() {
			@Override
			public void onMouseMove(MouseMoveEvent event) {
				DiagramController.this.onMouseMove(event);
			}
		}, MouseMoveEvent.getType());

		// ON MOUSE DOWN
		canvas.addDomHandler(new MouseDownHandler() {
			@Override
			public void onMouseDown(MouseDownEvent event) {
				DiagramController.this.onMouseDown(event);
			}
		}, MouseDownEvent.getType());

		// ON MOUSE UP
		canvas.addDomHandler(new MouseUpHandler() {
			@Override
			public void onMouseUp(MouseUpEvent event) {
				DiagramController.this.onMouseUp(event);
			}
		}, MouseUpEvent.getType());

		timer.scheduleRepeating(refreshRate);
		frameTimer.scheduleRepeating(1000);
		
		// Disable contextual menu
		disableContextMenu(widgetPanel.asWidget().getElement());
		disableContextMenu(canvas.asWidget().getElement());
	}

	/**
	 * Disable the context menu on a specified element
	 * 
	 * @param e the element where we want to disable the context menu
	 */
	public static native void disableContextMenu(Element e) /*-{
		e.oncontextmenu = function() { return false; };
	}-*/;

	/**
	 * Clear the diagram (connections and widgets)
	 */
	public void clearDiagram(){
		connectionSet.clear();
		shapeMap.clear();
		startFunctionWidget = null;
		buildConnection = null;
		
		// Restart widgetPanel
		widgetPanel.clear();
		widgetPanel.add(canvas.asWidget());
		canvas.getElement().getStyle().setPosition(Position.ABSOLUTE);
		showGrid(showGrid);
	}
	
	/**
	 * Draw a straight connection with an arrow between two GWT widgets. 
	 * The arrow is pointing to the second widget
	 * 
	 * @param startWidget Start widget
	 * @param endWidget End Widget
	 * @return the created new connection between the two widgets
	 */
	public Connection drawStraightArrowConnection(Widget startWidget, Widget endWidget){
		// Build Shape for the Widgets
		if(!shapeMap.containsKey(startWidget)){
			shapeMap.put(startWidget,new FunctionShape(this,startWidget));
		}
		Shape startShape = shapeMap.get(startWidget);
		if(!shapeMap.containsKey(endWidget)){
			shapeMap.put(endWidget,new FunctionShape(this,endWidget));
		}
		Shape endShape = shapeMap.get(endWidget);
		// Create Connection and Store it in the controller
		Connection c = new StraightArrowConnection(this, startShape, endShape);

		connectionSet.add(c);
		return c;
	}
	
	/**
	 * Draw a straight connection between two GWT widgets. The arrow is pointing to the second widget
	 * 
	 * @param startWidget Start widget
	 * @param endWidget End Widget
	 * @return the created new connection between the two widgets
	 */
	public Connection drawStraightConnection(Widget startWidget, Widget endWidget){
		// Build Shape for the Widgets
		if(!shapeMap.containsKey(startWidget)){
			shapeMap.put(startWidget,new FunctionShape(this,startWidget));
		}
		Shape startShape = shapeMap.get(startWidget);
		if(!shapeMap.containsKey(endWidget)){
			shapeMap.put(endWidget,new FunctionShape(this,endWidget));
		}
		Shape endShape = shapeMap.get(endWidget);
		// Create Connection and Store it in the controller
		Connection c = new StraightConnection(this, startShape, endShape);

		connectionSet.add(c);
		return c;
	}
	
	/**
	 * Add a widget on the diagram
	 * @param w the widget to add
	 * @param left left margin with the absolute panel
	 * @param top top margin with the absolute panel
	 */
	public void addWidget(final Widget w, int left, int top){
		w.getElement().getStyle().setZIndex(3);
		shapeMap.put(w,new FunctionShape(this,w));
		widgetPanel.add(w, left, top);
	}

	/**
	 * Add a widget as a decoration on a connection
	 * @param decoration widget that will be in the middle of the connection
	 * @param decoratedConnection the connection where the decoration will be put
	 */
	public void addDecoration(Widget decoration, Connection decoratedConnection){
		decoration.getElement().getStyle().setZIndex(10);
		decoration.getElement().getStyle().setPosition(Position.ABSOLUTE);
		widgetPanel.add(decoration);
		decoratedConnection.setDecoration(new DecorationShape(this, decoration));
	}
	
	/**
	 * Remove a decoration from the diagram
	 * 
	 * @param decoratedConnection connection where the decoration will be deleted
	 */
	public void removeDecoration( Connection decoratedConnection){
		DecorationShape decoShape = decoratedConnection.getDecoration();
		if(decoShape != null){
			widgetPanel.remove(decoShape.asWidget());
			decoratedConnection.removeDecoration();
		}
	}

	/**
	 * Change the background of the canvas by displaying or not a gray grid.
	 * @param showGrid if true, show a grid, else don't
	 */
	public void showGrid(boolean showGrid){
		this.showGrid = showGrid;
		backgroundCanvas.initGrid();
		if(this.showGrid){
			widgetPanel.add(backgroundCanvas.asWidget());
		}
		else{
			widgetPanel.remove(backgroundCanvas.asWidget());
		}
	}
	
	/**
	 * Get the diagram canvas
	 * @return the diagram canvas
	 */
	public DiagramCanvas getDiagramCanvas(){
		return canvas;
	}
	
	/**
	 * 
	 * @return the view where the widgets are displayed
	 */
	public AbsolutePanel getView(){
		return widgetPanel;
	}
	
	@Override
	public void fireEvent(GwtEvent<?> event) {
		handlerManager.fireEvent(event);
	}

	@Override
	public HandlerRegistration addUntieLinkHandler(UntieLinkHandler handler) {
		return handlerManager.addHandler(UntieLinkEvent.getType(), handler);
	}

	@Override
	public HandlerRegistration addTieLinkHandler(TieLinkHandler handler) {
		return handlerManager.addHandler(TieLinkEvent.getType(), handler);
	}

	@Override
	public HandlerRegistration addChangeOnDiagramHandler(
			ChangeOnDiagramHandler handler) {
		return handlerManager.addHandler(ChangeOnDiagramEvent.getType(), handler);
	}
	
	/**
	 * 
	 * @return true if on click, the connection will receive a new movable point
	 */
	public boolean isInEditionDragMovablePoint() {
		return inEditionDragMovablePoint;
	}

	/**
	 * 
	 * @return true if on click, a new build arrow will be drawn between the 
	 */
	public boolean isInEditionSelectableShapeToDrawConnection() {
		return inEditionSelectableShapeToDrawConnection;
	}

	/**
	 * 
	 * @return true if the user is building an arrow (the mouse is down and a arrow is displayed)
	 */
	public boolean isInDragBuildArrow() {
		return inDragBuildArrow;
	}

	/**
	 * 
	 * @return true if the user is dragging a movable point (the mouse is down and a curve is displayed)
	 */
	public boolean isInDragMovablePoint() {
		return inDragMovablePoint;
	}

	/**
	 * 
	 * @return true if a grid is displayed in background
	 */
	public boolean isShowGrid() {
		return showGrid;
	}

	
	// setup timer
	private final Timer timer = new Timer() {
		@Override
		public void run() {
			nFrame++;
			update();
		}
	};
	
	private final Timer frameTimer = new Timer() {
		@Override
		public void run() {
			long now = new Date().getTime();
			fps = (now-previousTime)!= 0 ? (nFrame - previousNFrame)*1000/(now-previousTime) : 0;
			previousNFrame = nFrame;
			previousTime = now;
		}
	};
	
	/**
	 * 
	 * @return the fps which are really displayed (frame per second)
	 */
	public long getFps(){
		return fps;
	}

	private void update(){
		//Restore canvas
		canvas.clear();

		// Redraw Connections
		for(Connection c : connectionSet){
			c.draw();
		}

		// Search for selectable area
		if(!inDragBuildArrow){
			for(Widget w : shapeMap.keySet()){
				FunctionShape s = shapeMap.get(w);
				if(s.isMouseNearSelectableArea(mousePoint)){
					s.highlightSelectableArea(mousePoint);
					inEditionSelectableShapeToDrawConnection = true;
					startFunctionWidget = w;
					RootPanel.getBodyElement().getStyle().setCursor(Cursor.POINTER);
					return;
				}
				inEditionSelectableShapeToDrawConnection = false;
			}
		}

		// Don't go deeper if in edition mode
		if(inDragBuildArrow){
			// If mouse over a widget, highlight it
			Widget w = getWidgetUnderMouse();
			if(w != null){
				highlightWidget(w);
			}
			clearAnimationsOnCanvas();
			return;
		}

		// Test if in Drag Movable Point
		if(!inDragMovablePoint && !inDragBuildArrow){
			for(Connection c : connectionSet){
				if(c.isMouseNearConnection(mousePoint)){
					highlightPoint = c.highlightMovablePoint(mousePoint);
					highlightConnection = getConnectionNearMouse();
					inEditionDragMovablePoint = true;
					RootPanel.getBodyElement().getStyle().setCursor(Cursor.POINTER);
					return;
				}
				inEditionDragMovablePoint = false;
			}
		}

		clearAnimationsOnCanvas();
	}

	private void clearAnimationsOnCanvas(){
		RootPanel.getBodyElement().getStyle().setCursor(Cursor.DEFAULT);
	}

	private void showLinkContextualMenu(){
		final Connection c = getConnectionNearMouse();
		if(c != null){
			final PopupPanel popupPanel = new PopupPanel(true);
			disableContextMenu(popupPanel.getElement());
			MenuBar popupMenuBar = new MenuBar(true);
			MenuItem deleteItem = new MenuItem("Delete", true, new Command() {
				@Override
				public void execute() {
					deleteConnection(c);
					removeDecoration(c);
					Widget startWidget = ((FunctionShape) c.getStartShape()).asWidget();
					Widget endWidget = ((FunctionShape) c.getEndShape()).asWidget();
					fireEvent(new UntieLinkEvent(startWidget, endWidget,c));
					popupPanel.hide();
				}
			});
			MenuItem straightItem = new MenuItem("Set straight", true, new Command() {
				@Override
				public void execute() {
					setStraightConnection(c);
					popupPanel.hide();
				}
			});

			popupPanel.setStyleName(LinksClientBundle.INSTANCE.css().connectionPopup());
			deleteItem.addStyleName(LinksClientBundle.INSTANCE.css().connectionPopupItem());
			straightItem.addStyleName(LinksClientBundle.INSTANCE.css().connectionPopupItem());

			popupMenuBar.addItem(deleteItem);
			popupMenuBar.addItem(straightItem);

			popupMenuBar.setVisible(true);
			popupPanel.add(popupMenuBar);
			popupPanel.setPopupPosition(mouseOffsetPoint.getLeft(), mouseOffsetPoint.getTop());
			popupPanel.show();
		}
	}

	private void onMouseMove(MouseMoveEvent event){
		int mouseX = event.getRelativeX(canvas.getElement());
		int mouseY = event.getRelativeY(canvas.getElement());
		mousePoint.setLeft(mouseX);
		mousePoint.setTop(mouseY);
		
		int offsetMouseX = event.getClientX();
		int offsetMouseY = event.getClientY();
		mouseOffsetPoint.setLeft(offsetMouseX);
		mouseOffsetPoint.setTop(offsetMouseY);
	}

	private void onMouseUp(MouseUpEvent event){

		// Test if Right Click
		if(event.getNativeButton() == NativeEvent.BUTTON_RIGHT){
			event.stopPropagation();
			event.preventDefault();
			showLinkContextualMenu();
			return;
		}

		if(inDragMovablePoint){
			movablePoint.setFixed(true);
			inDragMovablePoint = false;
			return;
		}

		if(inDragBuildArrow){
			Widget widgetSelected = getWidgetUnderMouse();
			if(widgetSelected != null && startFunctionWidget!= widgetSelected){
				Connection c = drawStraightArrowConnection(startFunctionWidget, widgetSelected);
				fireEvent(new TieLinkEvent(startFunctionWidget, widgetSelected, c));
			}
			canvas.setBackground();
			connectionSet.remove(buildConnection);
			inDragBuildArrow = false;
			buildConnection = null;
			for(Widget w : shapeMap.keySet()){
				removeHighlightWidget(w);
			}
			clearAnimationsOnCanvas();
		}

		if(inEditionDragMovablePoint){
			inEditionDragMovablePoint = false;
			clearAnimationsOnCanvas();
		}
	}

	private void onMouseDown(MouseDownEvent event){
		// Test if Right Click
		if(event.getNativeButton() == NativeEvent.BUTTON_RIGHT){
			return;
		}

		if(inEditionSelectableShapeToDrawConnection){
			inDragBuildArrow = true;
			inEditionSelectableShapeToDrawConnection = false;
			drawBuildArrow(startFunctionWidget, mousePoint);
			return;
		}

		if(inEditionDragMovablePoint){
			inDragMovablePoint = true;
			inEditionDragMovablePoint = false;
			movablePoint = highlightConnection.addMovablePoint(highlightPoint);
			movablePoint.setTrackPoint(mousePoint);
			return;
		}
	}

	/*
	 * CONNECTION MANAGEMENT METHODS
	 */

	private void deleteConnection(Connection c){
		connectionSet.remove(c);
	}

	private void setStraightConnection(Connection c){
		c.setStraight();
	}

	private Connection getConnectionNearMouse(){
		for(Connection c : connectionSet){
			if(c.isMouseNearConnection(mousePoint)){
				return c;
			}
		}
		return null;
	}
	
	private void drawBuildArrow(Widget startFunctionWidget, Point mousePoint){
		canvas.setForeground();
		Shape startShape = new FunctionShape(this,startFunctionWidget);
		final MouseShape endShape = new MouseShape(mousePoint);
		final Connection c = new StraightArrowConnection(this, startShape, endShape);
		connectionSet.add(c);
		buildConnection = c;
	}

	/*
	 * Build arrow utils method
	 */

	private Widget getWidgetUnderMouse(){
		for(Widget w : shapeMap.keySet()){
			Rectangle r = new Rectangle(shapeMap.get(w));
			removeHighlightWidget(w);
			if(r.isInside(mousePoint)){
				return w;
			}
		}
		return null;
	}

	private void highlightWidget(Widget w) {
		w.addStyleName(LinksClientBundle.INSTANCE.css().translucide());
	}

	private void removeHighlightWidget(Widget w){
		w.removeStyleName(LinksClientBundle.INSTANCE.css().translucide());
	}
}
