import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileFilter;

public class Start {
	private static int[] size;
	private static int scale=10;
	private static Start instance;
	public static void main(String[] args) {
		size=new int[]{32,24};
		if(args.length>=2)size=new int[]{Integer.parseInt(args[0]),Integer.parseInt(args[1])};
		else size=promptSize();
		instance=new Start();
	}
	private static int[] promptSize(){
		Lock l=new ReentrantLock();
		Condition done=l.newCondition();
		l.lock();
		JFrame f=new JFrame();
		f.setSize(300,200);
		JTextField cols=new JTextField("cols");
		JTextField rows=new JTextField("rows");
		JButton ok=new JButton("Start");
		JPanel p=new JPanel();
		f.add(p);
		ok.addActionListener(new ActionListener(){
			@Override
			public void actionPerformed(ActionEvent arg0) {
				l.lock();
				done.signalAll();
				f.setVisible(false);
				l.unlock();
			}
		});
		f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		p.add(cols);
		p.add(rows);
		p.add(ok);
		f.setVisible(true);
		try {
			done.await();
		} catch (InterruptedException e) {}
		l.unlock();
		return new int[]{Integer.parseInt(cols.getText()),Integer.parseInt(rows.getText())};
	}
	
	private JFrame frame=new JFrame();
	private JPanel panel;
	
	private ReentrantLock lock=new ReentrantLock();
	private Condition paused=lock.newCondition();
	
	private boolean pause=true;
	private boolean loopBorders=true;
	private boolean record=false;
	private File record_root=new File("./records");
	private int record_ID;
	private int record_off;
	
	private Menu menu;
	
	private int delay=10;
	
	private Color dead=Color.BLACK,alive=Color.GREEN.darker(),grid=Color.GRAY;
	
	private boolean[][] cells=new boolean[size[0]][size[1]];
	
	private JFileChooser chooser=new JFileChooser();
	private Random rand=new Random();
	
	{
		frame.setSize(size[0]*scale, size[1]*scale);
		panel=new JPanel(){
			public void paintComponent(Graphics g){
				draw((Graphics2D)g);
			}
		};
		frame.add(panel);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setUndecorated(true);
		frame.setVisible(true);
		new Thread(life()).start();
		frame.addKeyListener(new KeyListener(){
			@Override
			public void keyPressed(KeyEvent arg0) {
			}
			@Override
			public void keyReleased(KeyEvent arg0) {
			}
			@Override
			public void keyTyped(KeyEvent arg0) {
				char c=arg0.getKeyChar();
				if(c=='p') pause();
				if(c=='k') System.exit(0);
				if(c=='c') clear();
				if(c=='n') next();
				if(c=='m') menu();
				if(c=='r') recordSet(!record);
				if(c=='b') back();
			}
		});
		frame.addMouseListener(new MouseListener(){
			@Override
			public void mouseClicked(MouseEvent arg0) {
				int x=arg0.getX()/scale;
				int y=arg0.getY()/scale;
				reverseCell(x,y);
			}
			@Override
			public void mouseEntered(MouseEvent arg0) {}
			@Override
			public void mouseExited(MouseEvent arg0) {}
			@Override
			public void mousePressed(MouseEvent arg0) {}
			@Override
			public void mouseReleased(MouseEvent arg0) {}
		});
	}
	
	private void recordSet(boolean state){
		lock.lock();
		record=state;
		record_off=0;
		if(record){
			record_ID=rand.nextInt();
		}
		lock.unlock();
	}
	
	private void record_() throws FileNotFoundException{
		record_root.mkdirs();
		File descriptor=new File(record_root.getAbsolutePath()+File.separatorChar+record_ID+".rinfo");
		FileOutputStream fout=new FileOutputStream(descriptor,true);
		PrintStream pst=new PrintStream(fout);
		File outFile=new File(record_root.getAbsolutePath()+File.separatorChar+record_ID+'.'+record_off++).getAbsoluteFile();
		pst.println(outFile+".gtp");
		pst.close();
		save(outFile);
	}
	
	private void record(){
		if(!record) return;
		lock.lock();
		try{
			record_();
		}catch(Exception e){
		}
		lock.unlock();
	}
	private void back(){
		lock.lock();
		try{
			back_();
		}catch(Exception e){
			e.printStackTrace();
		}
		lock.unlock();
	}
	private void back_() throws IOException{
		if(record_ID==0||record_off==-1) return;
		if(record==true){
			recordSet(false);
			File descriptor=new File(record_root.getAbsolutePath()+File.separatorChar+record_ID+".rinfo");
			Stream<String> lines=Files.lines(descriptor.toPath());
			String[] l=lines.toArray(String[]::new);
			record_off=l.length-1;
			File inF=new File(l[record_off--]);
			open(inF);
		}else{
			File descriptor=new File(record_root.getAbsolutePath()+File.separatorChar+record_ID+".rinfo");
			Stream<String> lines=Files.lines(descriptor.toPath());
			String[] l=lines.toArray(String[]::new);
			File inF=new File(l[record_off--]);
			open(inF);
		}
	}
	
	private void next(){
		lock.lock();
		if(pause) pause();
		live();
		pause();
		lock.unlock();
	}
	
	private void menu(){
		if(menu==null){
			menu=new Menu(this);
			menu.pop();
		}else{
			menu.depop();
			menu=null;
		}
	}
	
	private class Menu{
		private Start gol;
		private ReentrantLock mlock=new ReentrantLock();
		private Condition cond=mlock.newCondition();
		
		private JFrame frame=new JFrame();
		private JPanel panel;
		
		private boolean inItem=false;
		private Item item;
		
		private Dimension size=new Dimension(300,200);
		
		private List<Item> items=new LinkedList<Item>();
		
		private Menu(Start start){
			this.gol=start;
			this.panel=new JPanel(){
				public void paintComponent(Graphics g){
					draw((Graphics2D)g);
				}
			};
			this.frame.add(panel);
			this.frame.setUndecorated(true);
			this.frame.addMouseListener(new MouseListener(){
				@Override
				public void mouseClicked(MouseEvent arg0) {}
				@Override
				public void mouseEntered(MouseEvent arg0) {}
				@Override
				public void mouseExited(MouseEvent arg0) {}
				@Override
				public void mousePressed(MouseEvent arg0) {
					item=getItem(arg0.getPoint());
					inItem=item!=null;
					if(inItem){
						item.actionMouse(true, translate(arg0.getPoint(),item.bounds));
					}
					panel.repaint();
				}
				@Override
				public void mouseReleased(MouseEvent arg0) {
					if(inItem){
						item.actionMouse(false, translate(arg0.getPoint(),item.bounds));
					}
					panel.repaint();
				}
			});
			this.frame.addKeyListener(new KeyListener(){
				@Override
				public void keyPressed(KeyEvent arg0) {}
				@Override
				public void keyReleased(KeyEvent arg0) {}
				@Override
				public void keyTyped(KeyEvent arg0) {
					if(inItem){
						item.actionKey(arg0.getKeyChar());
					}else{
						char c=arg0.getKeyChar();
						if(c=='m') depop();
					}
					panel.repaint();
				}
			});
		}
		private Item getItem(Point pos){
			for(Item it:items){
				if(it.bounds.contains(pos)) return it;
			}
			return null;
		}
		private void pop(){
			frame.setSize(size.width, size.height);
			frame.setVisible(true);
			frame.requestFocus();
			addItems();
		}
		private void depop(){
			frame.setVisible(false);
			gol.menu=null;
		}
		private void draw(Graphics2D g){
			Rectangle r=new Rectangle(new Point(0,0),size);
			g.setPaint(Color.BLACK);
			g.fill(r);
			r=new Rectangle(0,0,size.width-1,size.height-1);
			g.setPaint(Color.LIGHT_GRAY);
			g.draw(r);
			for(Item i:items){
				BufferedImage img=i.draw();
				g.drawImage(img, i.bounds.x, i.bounds.y, null);
			}
			r=new Rectangle(0,0,size.width-1,size.height-1);
			g.setPaint(Color.LIGHT_GRAY);
			g.draw(r);
		}
		
		private void addItems(){
			items.add(new Item(){
				{
					bounds=new Rectangle(new Point(1,1),new Dimension(148,20));
				}
				protected void draw(Graphics2D g){
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(19,19));
					g.setPaint(Color.RED);
					if(gol.loopBorders) g.fill(r);
					g.setPaint(Color.WHITE);
					g.draw(r);
					g.drawString("Connected sides", 25, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						gol.lock.lock();
						gol.loopBorders=!gol.loopBorders;
						gol.lock.unlock();
					}
				}
			});
			items.add(new Item(){
				private int componentSelected=0;
				int off=0;
				private char[][] contents=new char[][]{
					{'0','0','0'},
					{'0','0','0'},
					{'0','0','0'},
				};
				{
					bounds=new Rectangle(new Point(1,21),new Dimension(298,20));
					int r=gol.alive.getRed();
					int g=gol.alive.getGreen();
					int b=gol.alive.getBlue();
					String sr=r+"";
					String sg=g+"";
					String sb=b+"";
					char[] rc=sr.toCharArray();
					if(rc.length==3) contents[0]=rc;
					else if(rc.length==2) { contents[0][1]=rc[0];contents[0][2]=rc[1];}
					else if(rc.length==1) contents[0][2]=rc[0];
					char[] rg=sg.toCharArray();
					if(rg.length==3) contents[1]=rg;
					else if(rg.length==2) { contents[1][1]=rg[0];contents[1][2]=rg[1];}
					else if(rg.length==1) contents[1][2]=rg[0];
					char[] rb=sb.toCharArray();
					if(rb.length==3) contents[2]=rb;
					else if(rb.length==2) { contents[2][1]=rb[0];contents[2][2]=rb[1];}
					else if(rb.length==1) contents[2][2]=rb[0];
				}
				protected void draw(Graphics2D g){
					g.setPaint(Color.WHITE);
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(40,19));
					g.draw(r);
					r=new Rectangle(new Point(40,0),new Dimension(40,19));
					g.draw(r);
					r=new Rectangle(new Point(80,0),new Dimension(40,19));
					g.draw(r);
					g.drawString(new String(contents[0]),5,18);
					g.drawString(new String(contents[1]),45,18);
					g.drawString(new String(contents[2]),85,18);
					g.drawString("Alive color", 125, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						int x=p.x;
						if(x>=0&&x<40) componentSelected=0;
						else if(x>=40&&x<80) componentSelected=1;
						else if(x>=80&&x<120) componentSelected=2;
						off=0;
						System.out.println(componentSelected);
					}
				}
				protected void actionKey(char c){
					if(c=='0'||c=='1'||c=='2'||c=='3'||c=='4'||c=='5'||c=='6'||c=='7'||c=='8'||c=='9'){
						if(off!=3){
							contents[componentSelected][off++]=c;
							gol.lock.lock();
							int r=Integer.parseInt(new String(contents[0]));
							int g=Integer.parseInt(new String(contents[1]));
							int b=Integer.parseInt(new String(contents[2]));
							if(r>255){
								r=255;
								contents[0]=new char[]{'2','5','5'};
							}
							if(g>255){
								g=255;
								contents[1]=new char[]{'2','5','5'};
							}
							if(b>255){
								b=255;
								contents[2]=new char[]{'2','5','5'};
							}
							gol.alive=new Color(r,g,b);
							gol.lock.unlock();
							gol.panel.repaint();
						}
					}
				}
			});
			items.add(new Item(){
				private int componentSelected=0;
				int off=0;
				private char[][] contents=new char[][]{
					{'0','0','0'},
					{'0','0','0'},
					{'0','0','0'},
				};
				{
					bounds=new Rectangle(new Point(1,41),new Dimension(298,20));
					int r=gol.dead.getRed();
					int g=gol.dead.getGreen();
					int b=gol.dead.getBlue();
					String sr=r+"";
					String sg=g+"";
					String sb=b+"";
					char[] rc=sr.toCharArray();
					if(rc.length==3) contents[0]=rc;
					else if(rc.length==2) { contents[0][1]=rc[0];contents[0][2]=rc[1];}
					else if(rc.length==1) contents[0][2]=rc[0];
					char[] rg=sg.toCharArray();
					if(rg.length==3) contents[1]=rg;
					else if(rg.length==2) { contents[1][1]=rg[0];contents[1][2]=rg[1];}
					else if(rg.length==1) contents[1][2]=rg[0];
					char[] rb=sb.toCharArray();
					if(rb.length==3) contents[2]=rb;
					else if(rb.length==2) { contents[2][1]=rb[0];contents[2][2]=rb[1];}
					else if(rb.length==1) contents[2][2]=rb[0];
				}
				protected void draw(Graphics2D g){
					g.setPaint(Color.WHITE);
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(40,19));
					g.draw(r);
					r=new Rectangle(new Point(40,0),new Dimension(40,19));
					g.draw(r);
					r=new Rectangle(new Point(80,0),new Dimension(40,19));
					g.draw(r);
					g.drawString(new String(contents[0]),5,18);
					g.drawString(new String(contents[1]),45,18);
					g.drawString(new String(contents[2]),85,18);
					g.drawString("Dead color", 125, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						int x=p.x;
						if(x>=0&&x<40) componentSelected=0;
						else if(x>=40&&x<80) componentSelected=1;
						else if(x>=80&&x<120) componentSelected=2;
						off=0;
						System.out.println(componentSelected);
					}
				}
				protected void actionKey(char c){
					if(c=='0'||c=='1'||c=='2'||c=='3'||c=='4'||c=='5'||c=='6'||c=='7'||c=='8'||c=='9'){
						if(off!=3){
							contents[componentSelected][off++]=c;
							gol.lock.lock();
							int r=Integer.parseInt(new String(contents[0]));
							int g=Integer.parseInt(new String(contents[1]));
							int b=Integer.parseInt(new String(contents[2]));
							if(r>255){
								r=255;
								contents[0]=new char[]{'2','5','5'};
							}
							if(g>255){
								g=255;
								contents[1]=new char[]{'2','5','5'};
							}
							if(b>255){
								b=255;
								contents[2]=new char[]{'2','5','5'};
							}
							gol.dead=new Color(r,g,b);
							gol.lock.unlock();
							gol.panel.repaint();
						}
					}
				}
			});items.add(new Item(){
				private int componentSelected=0;
				int off=0;
				private char[][] contents=new char[][]{
					{'0','0','0'},
					{'0','0','0'},
					{'0','0','0'},
				};
				{
					bounds=new Rectangle(new Point(1,61),new Dimension(298,20));
					int r=gol.grid.getRed();
					int g=gol.grid.getGreen();
					int b=gol.grid.getBlue();
					String sr=r+"";
					String sg=g+"";
					String sb=b+"";
					char[] rc=sr.toCharArray();
					if(rc.length==3) contents[0]=rc;
					else if(rc.length==2) { contents[0][1]=rc[0];contents[0][2]=rc[1];}
					else if(rc.length==1) contents[0][2]=rc[0];
					char[] rg=sg.toCharArray();
					if(rg.length==3) contents[1]=rg;
					else if(rg.length==2) { contents[1][1]=rg[0];contents[1][2]=rg[1];}
					else if(rg.length==1) contents[1][2]=rg[0];
					char[] rb=sb.toCharArray();
					if(rb.length==3) contents[2]=rb;
					else if(rb.length==2) { contents[2][1]=rb[0];contents[2][2]=rb[1];}
					else if(rb.length==1) contents[2][2]=rb[0];
				}
				protected void draw(Graphics2D g){
					g.setPaint(Color.WHITE);
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(40,19));
					g.draw(r);
					r=new Rectangle(new Point(40,0),new Dimension(40,19));
					g.draw(r);
					r=new Rectangle(new Point(80,0),new Dimension(40,19));
					g.draw(r);
					g.drawString(new String(contents[0]),5,18);
					g.drawString(new String(contents[1]),45,18);
					g.drawString(new String(contents[2]),85,18);
					g.drawString("Grid color", 125, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						int x=p.x;
						if(x>=0&&x<40) componentSelected=0;
						else if(x>=40&&x<80) componentSelected=1;
						else if(x>=80&&x<120) componentSelected=2;
						off=0;
						System.out.println(componentSelected);
					}
				}
				protected void actionKey(char c){
					if(c=='0'||c=='1'||c=='2'||c=='3'||c=='4'||c=='5'||c=='6'||c=='7'||c=='8'||c=='9'){
						if(off!=3){
							contents[componentSelected][off++]=c;
							gol.lock.lock();
							int r=Integer.parseInt(new String(contents[0]));
							int g=Integer.parseInt(new String(contents[1]));
							int b=Integer.parseInt(new String(contents[2]));
							if(r>255){
								r=255;
								contents[0]=new char[]{'2','5','5'};
							}
							if(g>255){
								g=255;
								contents[1]=new char[]{'2','5','5'};
							}
							if(b>255){
								b=255;
								contents[2]=new char[]{'2','5','5'};
							}
							gol.grid=new Color(r,g,b);
							gol.lock.unlock();
							gol.panel.repaint();
						}
					}
				}
			});
			items.add(new Item(){
				{
					bounds=new Rectangle(new Point(151,1),new Dimension(148,20));
				}
				protected void draw(Graphics2D g){
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(19,19));
					g.setPaint(Color.WHITE);
					g.draw(r);
					g.drawString("Clear grid", 25, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						gol.lock.lock();
						gol.clear();
						gol.lock.unlock();
					}
				}
			});
			items.add(new Item(){
				{
					bounds=new Rectangle(new Point(1,81),new Dimension(298,20));
				}
				protected void draw(Graphics2D g){
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(298,19));
					g.setPaint(Color.WHITE);
					g.draw(r);
					g.drawString("Open template", 5, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						gol.chooser.setDialogTitle("Open template");
						FileFilter ff=new FileFilter(){
							@Override
							public boolean accept(File arg0) {
								return arg0.getName().endsWith(".gtp");
							}
							@Override
							public String getDescription() {
								return "Templates (*.gtp)";
							}
						};
						gol.chooser.resetChoosableFileFilters();
						gol.chooser.addChoosableFileFilter(ff);
						gol.chooser.setFileFilter(ff);
						int ret=gol.chooser.showOpenDialog(panel);
						if(ret==JFileChooser.APPROVE_OPTION){
							if(!gol.pause) gol.pause();
							gol.open(gol.chooser.getSelectedFile());
						}
					}
				}
			});
			items.add(new Item(){
				{
					bounds=new Rectangle(new Point(1,101),new Dimension(298,20));
				}
				protected void draw(Graphics2D g){
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(298,19));
					g.setPaint(Color.WHITE);
					g.draw(r);
					g.drawString("Save template", 5, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						gol.chooser.setDialogTitle("Save template");
						FileFilter ff=new FileFilter(){
							@Override
							public boolean accept(File arg0) {
								return arg0.getName().endsWith(".gtp");
							}
							@Override
							public String getDescription() {
								return "Templates (*.gtp)";
							}
						};
						gol.chooser.resetChoosableFileFilters();
						gol.chooser.addChoosableFileFilter(ff);
						gol.chooser.setFileFilter(ff);
						int ret=gol.chooser.showSaveDialog(panel);
						if(ret==JFileChooser.APPROVE_OPTION){
							if(!gol.pause) gol.pause();
							gol.save(gol.chooser.getSelectedFile());
						}
					}
				}
			});
			items.add(new Item(){
				private int componentSelected=0;
				int off=0;
				private char[] content=new char[]{
					'0','0','0','0','0'
				};
				{
					bounds=new Rectangle(new Point(1,121),new Dimension(298,20));
					int r=gol.delay;
					String sr=r+"";
					char[] rc=sr.toCharArray();
					if(rc.length==5) content=rc;
					else if(rc.length==4) {
						content[1]=rc[0];
						content[2]=rc[1];
						content[3]=rc[2];
						content[4]=rc[3];
					}
					else if(rc.length==3) {
						content[1]=rc[0];
						content[2]=rc[1];
						content[3]=rc[2];
					}
					else if(rc.length==2) { content[1]=rc[0];content[2]=rc[1];}
					else if(rc.length==1) content[2]=rc[0];
				}
				protected void draw(Graphics2D g){
					g.setPaint(Color.WHITE);
					Rectangle r=new Rectangle(new Point(0,0),new Dimension(80,19));
					g.draw(r);
					g.drawString(new String(content),5,18);
					g.drawString("Delay", 100, 18);
				}
				protected void actionMouse(boolean press, Point p){
					if(press){
						off=0;
					}
				}
				protected void actionKey(char c){
					if(c=='0'||c=='1'||c=='2'||c=='3'||c=='4'||c=='5'||c=='6'||c=='7'||c=='8'||c=='9'){
						if(off!=5){
							content[off++]=c;
							gol.lock.lock();
							int delay=Integer.parseInt(new String(content));
							gol.delay=delay;
							gol.lock.unlock();
						}
					}
				}
			});
		}
		
		private Point translate(Point from, Rectangle to){
			return new Point(from.x-to.x,from.y-to.y);
		}
		
		private class Item{
			protected Rectangle bounds=new Rectangle();
			private BufferedImage draw(){
				BufferedImage img=new BufferedImage((int)bounds.getWidth(),(int)bounds.getHeight(),BufferedImage.TYPE_INT_RGB);
				Graphics2D g=img.createGraphics();
				draw(g);
				g.dispose();
				return img;
			}
			protected void draw(Graphics2D g){}
			protected void actionMouse(boolean press,Point p){}
			protected void actionKey(char key){}
		}
	}
	
	private void open(File template){
		lock.lock();
		clear();
		try {
			FileInputStream in=new FileInputStream(template);
			DataInputStream din=new DataInputStream(in);
			int w=din.readInt();
			int h=din.readInt();
			if(size[0]>=w&&size[1]>=h){
				for(int i=0;i<w;i++){
					for(int j=0;j<h;j++){
						cells[i][j]=din.readBoolean();
					}
				}
			}else{
				System.out.println("area too small");
			}
			din.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		lock.unlock();
	}
	private void save(File template){
		if(!template.getName().endsWith(".gtp")) template=new File(template.getAbsolutePath()+".gtp");
		lock.lock();
		try {
			FileOutputStream fout = new FileOutputStream(template);
			DataOutputStream dout=new DataOutputStream(fout);
			dout.writeInt(size[0]);
			dout.writeInt(size[1]);
			for(int i=0;i<size[0];i++){
				for(int j=0;j<size[1];j++){
					dout.writeBoolean(cells[i][j]);
				}
			}
			dout.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		lock.unlock();
	}
	
	private void live(){
		lock.lock();
		while(pause){
			try {
				paused.await();
			} catch (InterruptedException e) {lock.unlock();System.exit(1);}
		}
		record();
		boolean[][] buffer=new boolean[size[0]][size[1]];
		copy(cells,buffer);
		for(int i=0;i<size[0];i++){
			for(int j=0;j<size[1];j++){
				processCell(i,j,buffer);
			}
		}
		cells=buffer;
		lock.unlock();
		panel.repaint();
	}
	
	private void copy(boolean[][] from, boolean[][] to){
		for(int i=0;i<from.length;i++) for(int j=0;j<from[i].length;j++) to[i][j]=from[i][j];
	}
	
	private void reverseCell(int x, int y){
		lock.lock();
		cells[x][y]=!cells[x][y];
		lock.unlock();
		panel.repaint();
	}
	
	private void processCell(int x, int y, boolean[][] buffer){
		int neighbors=getNeighbors(x,y);
		if(cells[x][y]){
			if(neighbors==2||neighbors==3);
			else buffer[x][y]=false;
		}else{
			if(neighbors==3) buffer[x][y]=true;
			else;
		}
	}
	
	private int getNeighbors(int x, int y){
		int n=0;
		n+=i(getCell(x-1,y-1));
		n+=i(getCell(x,y-1));
		n+=i(getCell(x+1,y-1));
		n+=i(getCell(x-1,y));
		n+=i(getCell(x+1,y));
		n+=i(getCell(x-1,y+1));
		n+=i(getCell(x,y+1));
		n+=i(getCell(x+1,y+1));
		return n;
	}
	
	private boolean getCell(int x, int y){
		if(loopBorders){
			if(x<0) return getCell(x+size[0],y);
			if(y<0) return getCell(x,y+size[1]);
			if(x>=size[0]) return getCell(x-size[0],y);
			if(y>=size[1]) return getCell(x,y-size[1]);
		}else{
			if(x<0) return false;
			if(y<0) return false;
			if(x>=size[0]) return false;
			if(y>=size[1]) return false;
		}
		return cells[x][y];
	}
	
	private int i(boolean i){
		return i?1:0;
	}
	
	private Runnable life(){
		return new Runnable(){
			public void run(){
				while(true){
					live();
					try {
						Thread.sleep(delay);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		};
	}
	
	private void clear(){
		lock.lock();
		for(int i=0;i<size[0];i++) for(int j=0;j<size[1];j++) cells[i][j]=false;
		pause=true;
		lock.unlock();
		panel.repaint();
	}
	
	private void pause(){
		lock.lock();
		pause=!pause;
		paused.signalAll();
		lock.unlock();
	}
	
	private void draw(Graphics2D g){
		lock.lock();
		for(int i=0;i<size[0];i++){
			for(int j=0;j<size[1];j++){
				Rectangle cell=new Rectangle(new Point(scale*i,scale*j),new Dimension(scale,scale));
				g.setPaint(cells[i][j]?alive:dead);
				g.fill(cell);
				g.setPaint(grid);
				g.draw(cell);
			}
		}
		lock.unlock();
	}
}
