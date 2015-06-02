package modbus;

import java.util.Arrays;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;

import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;

public class SlaveFolder {
	protected ModbusLink link;
	protected Node node;
	protected SlaveNode root;
	
	SlaveFolder(ModbusLink link, Node node) {
		this.link = link;
		this.node = node;
		node.setAttribute("restoreType", new Value("folder"));
		
		Action act = new Action(Permission.READ, new AddPointHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("type", ValueType.makeEnum("COIL", "DISCRETE", "HOLDING", "INPUT")));
		act.addParameter(new Parameter("offset", ValueType.NUMBER));
		act.addParameter(new Parameter("number of registers", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("data type", ValueType.makeEnum("BOOLEAN", "INT32", "INT16", "UINT32", "UINT16", "INT32M10K", "UINT32M10K", "INT32SWAP", "UINT32SWAP", "BCD16")));
		act.addParameter(new Parameter("scaling", ValueType.NUMBER, new Value(1)));
		act.addParameter(new Parameter("writable", ValueType.BOOL, new Value(false)));
		node.createChild("add point").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new AddFolderHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("add folder").setAction(act).build().setSerializable(false);
	}
	
	SlaveFolder(ModbusLink link, Node node, SlaveNode root) {
		this(link, node);
		this.root = root;
		
	}
	
	void restoreLastSession() {
		if (node.getChildren() == null) return;
		for  (Node child: node.getChildren().values()) {
			Value restoreType = child.getAttribute("restoreType");
			if (restoreType != null) {
				if (restoreType.getString().equals("folder")) {
					SlaveFolder sf = new SlaveFolder(link, child, root);
					sf.restoreLastSession();
				} else if (restoreType.getString().equals("point")) {
					setupPointActions(child);
					link.setupPoint(child, root);
				}
			} else if (child.getAction() == null) {
				node.removeChild(child);
			}
		}
	}
	
	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}
	
	protected void remove() {
		node.clearChildren();
		node.getParent().removeChild(node);
	}
	
	protected class AddFolderHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node child = node.createChild(name).build();
			new SlaveFolder(link, child, root);
		}
	}
	
	protected enum PointType {COIL, DISCRETE, HOLDING, INPUT}
	protected enum DataType {INT32, INT16, UINT32, UINT16, INT32M10K, UINT32M10K, BOOLEAN, INT32SWAP, UINT32SWAP, BCD16 }
	
	protected class AddPointHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			PointType type;
			try {
				type = PointType.valueOf(event.getParameter("type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				System.out.println("invalid type");
				e.printStackTrace();
				return;
			}
			int offset = event.getParameter("offset", ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter("number of registers", ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING) && event.getParameter("writable", ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE) dataType = DataType.BOOLEAN;
			else try {
				dataType = DataType.valueOf(event.getParameter("data type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e1) {
				System.out.println("invalid data type");
				e1.printStackTrace();
				return;
			}
			double scaling = event.getParameter("scaling", ValueType.NUMBER).getNumber().doubleValue();
			Node pnode = node.createChild(name).setValueType(ValueType.STRING).build();
			pnode.setAttribute("type", new Value(type.toString()));
			pnode.setAttribute("offset", new Value(offset));
			pnode.setAttribute("number of registers", new Value(numRegs));
			pnode.setAttribute("data type", new Value(dataType.toString()));
			pnode.setAttribute("scaling", new Value(scaling));
			pnode.setAttribute("writable", new Value(writable));
			setupPointActions(pnode);
			link.setupPoint(pnode, root);
			pnode.setAttribute("restoreType", new Value("point"));
		}
	}
	
	protected void setupPointActions(Node pointNode) {
		Action act = new Action(Permission.READ, new RemovePointHandler(pointNode));
		pointNode.createChild("remove").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new EditPointHandler(pointNode));
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(pointNode.getName())));
		act.addParameter(new Parameter("type", ValueType.makeEnum("COIL", "DISCRETE", "HOLDING", "INPUT"), pointNode.getAttribute("type")));
		act.addParameter(new Parameter("offset", ValueType.NUMBER, pointNode.getAttribute("offset")));
		act.addParameter(new Parameter("number of registers", ValueType.NUMBER, pointNode.getAttribute("number of registers")));
		act.addParameter(new Parameter("data type", ValueType.makeEnum("BOOLEAN", "INT32", "INT16", "UINT32", "UINT16", "INT32M10K", "UINT32M10K", "INT32SWAP", "UINT32SWAP", "BCD16"), pointNode.getAttribute("data type")));
		act.addParameter(new Parameter("scaling", ValueType.NUMBER, pointNode.getAttribute("scaling")));
		act.addParameter(new Parameter("writable", ValueType.BOOL, pointNode.getAttribute("writable")));
		pointNode.createChild("edit").setAction(act).build().setSerializable(false);
		
		boolean writable = pointNode.getAttribute("writable").getBool();
		if (writable) {
			act = new Action(Permission.READ, new SetHandler(pointNode));
			act.addParameter(new Parameter("value", ValueType.STRING));
			pointNode.createChild("set").setAction(act).build().setSerializable(false);
		}
	}
	
	protected class EditPointHandler implements Handler<ActionResult> {
		private Node pointNode;
		EditPointHandler(Node pnode) {
			pointNode = pnode;
		}
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			PointType type;
			try {
				type = PointType.valueOf(event.getParameter("type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e) {
				System.out.println("invalid type");
				e.printStackTrace();
				return;
			}
			int offset = event.getParameter("offset", ValueType.NUMBER).getNumber().intValue();
			int numRegs = event.getParameter("number of registers", ValueType.NUMBER).getNumber().intValue();
			boolean writable = (type == PointType.COIL || type == PointType.HOLDING) && event.getParameter("writable", ValueType.BOOL).getBool();
			DataType dataType;
			if (type == PointType.COIL || type == PointType.DISCRETE) dataType = DataType.BOOLEAN;
			else try {
				dataType = DataType.valueOf(event.getParameter("data type", ValueType.STRING).getString().toUpperCase());
			} catch (Exception e1) {
				System.out.println("invalid data type");
				e1.printStackTrace();
				return;
			}
			double scaling = event.getParameter("scaling", ValueType.NUMBER).getNumber().doubleValue();
			
			if (!name.equals(pointNode.getName())) {
				Node newnode = node.createChild(name).build();
				node.removeChild(pointNode);
				pointNode = newnode;
			}
			pointNode.setAttribute("type", new Value(type.toString()));
			pointNode.setAttribute("offset", new Value(offset));
			pointNode.setAttribute("number of registers", new Value(numRegs));
			pointNode.setAttribute("data type", new Value(dataType.toString()));
			pointNode.setAttribute("scaling", new Value(scaling));
			pointNode.setAttribute("writable", new Value(writable));
			pointNode.clearChildren();
			setupPointActions(pointNode);
			link.setupPoint(pointNode, root);
			pointNode.setAttribute("restoreType", new Value("point"));
		}
	}
	
	protected class RemovePointHandler implements Handler<ActionResult> {
		private Node toRemove;
		RemovePointHandler(Node pnode){
			toRemove = pnode;
		}
		public void handle(ActionResult event) {
			node.removeChild(toRemove);
		}
	}
	
	protected void readPoint(Node pointNode) {
		PointType type = PointType.valueOf(pointNode.getAttribute("type").getString());
		int offset = pointNode.getAttribute("offset").getNumber().intValue();
		int numRegs = pointNode.getAttribute("number of registers").getNumber().intValue();
		int id = root.node.getAttribute("slave id").getNumber().intValue();
		double scaling = pointNode.getAttribute("scaling").getNumber().doubleValue();
		DataType dataType = DataType.valueOf(pointNode.getAttribute("data type").getString());
		ModbusRequest request=null;
		JsonArray val = new JsonArray();
		try {
			switch (type) {
			case COIL: request = new ReadCoilsRequest(id, offset, numRegs);break;
			case DISCRETE: request = new ReadDiscreteInputsRequest(id, offset, numRegs);break;
			case HOLDING: request = new ReadHoldingRegistersRequest(id, offset, numRegs);break;
			case INPUT: request = new ReadInputRegistersRequest(id, offset, numRegs);break;
			}
			ReadResponse response = (ReadResponse) root.master.send(request);
			if (response.getExceptionCode()!=-1) {
				System.out.println("errorresponse"+response.getExceptionMessage());
				return;
			}
			if (type == PointType.COIL || type == PointType.DISCRETE) {
				boolean[] booldat = response.getBooleanData();
				System.out.println("it works?" +Arrays.toString(booldat));
				for (int j=0;j<numRegs;j++) {
					boolean b = booldat[j];
					val.addBoolean(b);
				}
			} else {
				System.out.println(Arrays.toString(response.getShortData()));
				val = parseResponse(response.getShortData(), dataType, scaling);
			}
		} catch (ModbusTransportException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String valString = val.toString();
		if (val.size() == 1) valString = val.get(0).toString();
		pointNode.setValue(new Value(valString));
	}
	
	protected class SetHandler implements Handler<ActionResult> {
		private Node vnode;
		SetHandler(Node vnode) {
			this.vnode = vnode;
		}
		public void handle(ActionResult event) {
			PointType type = PointType.valueOf(vnode.getAttribute("type").getString());
			int offset = vnode.getAttribute("offset").getNumber().intValue();
			int id = root.node.getAttribute("slave id").getNumber().intValue();
			DataType dataType = DataType.valueOf(vnode.getAttribute("data type").getString());
			double scaling = vnode.getAttribute("scaling").getNumber().doubleValue();
			String oldstr = vnode.getValue().getString();
			if (!oldstr.startsWith("[")) oldstr = "["+oldstr+"]";
			int numThings = new JsonArray(oldstr).size();
			String valstr = event.getParameter("value", ValueType.STRING).getString();
			if (!valstr.startsWith("[")) valstr = "["+valstr+"]";
			JsonArray valarr = new JsonArray(valstr);
			if (valarr.size() != numThings) {
				System.out.println("wrong number of values");
				return;
			}
			ModbusRequest request = null;
			try {
				switch (type) {
				case COIL: request = new WriteCoilsRequest(id, offset, makeBoolArr(valarr));break;
				case HOLDING: request = new WriteRegistersRequest(id, offset, makeShortArr(valarr, dataType, scaling));break;
				default:break;
				}
				if (request!=null) System.out.println("set request: " + request.toString());
				ModbusResponse response = root.master.send(request);
				System.out.println(response.getExceptionMessage());
			} catch (ModbusTransportException e) {
				// TODO Auto-generated catch block
				System.out.println("Modbus transpot exception");
				e.printStackTrace();
				return;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				System.out.println("make arr exception");
				e.printStackTrace();
				return;
			}
			
		}
	}
	
	protected static boolean[] makeBoolArr(JsonArray jarr) throws Exception {
		boolean[] retval = new boolean[jarr.size()];
		for (int i=0;i<jarr.size();i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Boolean)) throw new Exception("not a boolean array");
			else retval[i] = (boolean) o;
		}
		return retval;
	}
	
	protected static short[] makeShortArr(JsonArray jarr, DataType dt, double scaling) throws Exception {
		short[] retval = null;
		if (dt == DataType.BOOLEAN) {
			retval = new short[(int)Math.ceil((double)jarr.size()/16)];
			for (int i=0;i<retval.length;i++) {
				short element = 0;
				for (int j=0;j<16;j++) {
					int bit = 0;
					if (i+j < jarr.size()) {
						Object o = jarr.get(i+j);
						if (!(o instanceof Boolean)) throw new Exception("not a boolean array");
						if ((boolean) o ) bit = 1;
					}
					element = (short) (element & (bit << (15 - j)));
					jarr.get(i+j);
				}
				retval[i] = element;
			}
			return retval;
		}
		if (dt == DataType.INT16 || dt == DataType.UINT16 || dt == DataType.BCD16) retval = new short[jarr.size()];
		else retval = new short[2*jarr.size()];
		for (int i=0;i<jarr.size();i++) {
			Object o = jarr.get(i);
			if (!(o instanceof Number)) throw new Exception("not an int array");
			Number n = ((Number) o).doubleValue()*scaling;
			switch (dt) {
			case INT16:
			case UINT16: retval[i] =n.shortValue(); break;
			case INT32:
			case UINT32: { 
				long aslong = n.longValue();
				retval[i/2] = (short) (aslong/65536);
				retval[(i/2)+1] = (short) (aslong%65536); break;
			}
			case INT32SWAP:
			case UINT32SWAP: {
				long aslong = n.longValue();
				retval[(i/2)+1] = (short) (aslong/65536);
				retval[(i/2)] = (short) (aslong%65536); break;
			}
			case INT32M10K:
			case UINT32M10K: { 
				long aslong = n.longValue();
				retval[i/2] = (short) (aslong/10000);
				retval[(i/2)+1] = (short) (aslong%10000); break;
			}
			case BCD16: {
				short bcd = 0;
				for (int j=0;j<4;j++) {
					int d = (n.intValue()%((int)Math.pow(10, j+1)))/(int)(Math.pow(10, j));
					bcd = (short) (bcd | (d << (j*4)));
				}
				retval[i] = bcd; break;
			}
			default: break;
			}
			
		}
		return retval;
	}
	
	protected JsonArray parseResponse(short[] responseData, DataType dataType, double scaling) {
		JsonArray retval = new JsonArray();
		int last = 0;
		int regnum = 0;
		for (short s: responseData) {
			switch (dataType) {
			case INT16: retval.addNumber(s/scaling);break;
			case UINT16: retval.addNumber(Short.toUnsignedInt(s)/scaling);break;
			case INT32: if (regnum == 0) {
					regnum += 1;
					last = s;
				} else {
					regnum = 0;
					int num = last*65536 + Short.toUnsignedInt(s);
					retval.addNumber(num/scaling);
				}
				break;
			case INT32SWAP: if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					int num = s*65536 + last;
					retval.addNumber(num/scaling);
				}
				break;
			case UINT32:  if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					long num = Integer.toUnsignedLong(last*65536 + Short.toUnsignedInt(s));
					retval.addNumber(num/scaling);
				}
				break;
			case UINT32SWAP:  if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					long num = Integer.toUnsignedLong(Short.toUnsignedInt(s)*65536 + last);
					retval.addNumber(num/scaling);
				}
				break;
			case INT32M10K: if (regnum == 0) {
					regnum += 1;
					last = s;
				} else {
					regnum = 0;
					int num = last*10000 + s;
					retval.addNumber(num/scaling);
				}
				break;
			case UINT32M10K: if (regnum == 0) {
					regnum += 1;
					last = Short.toUnsignedInt(s);
				} else {
					regnum = 0;
					long num = Integer.toUnsignedLong(last*10000 + Short.toUnsignedInt(s));
					retval.addNumber(num/scaling);
				}
				break;
			case BCD16: {
				int total = 0;
				for (int i=0;i<4;i++) {
					int d = getDigitFromBcd(s, i);
					total = total + d*((int) Math.pow(10, i));
				}
				retval.addNumber(total/scaling);
				}
				break;
			case BOOLEAN: for (int i=0; i<16; i++) {
					retval.addBoolean(((s >> i) & 1) != 0);
				}
				break;
			default: retval.addString("oops");break;
			}
		}
		return retval;
	}
	
	private int getDigitFromBcd(int bcd, int position) {
		return (bcd >>> (position*4)) & 15;
	}
	

}