import java.time.*;
import java.util.*;

/** Pure, deterministic fictional history. Amounts are KRW; timestamps are converted at persistence. */
public final class YoonseoPlan {
    public static final LocalDate DEMO = LocalDate.of(2026,11,18);
    public static final LocalDate FROM = LocalDate.of(2026,7,1);
    public record Payment(LocalDate day, String card, String brand, long amount, long benefit, String source) {}
    public record Movement(LocalDate day, LocalTime time, String label, long signedAmount) {}
    public record Row(Movement movement, long balance) {}
    public record Plan(List<Payment> payments, List<Row> bankRows, long opening, long closing) {}

    public static Plan build(LocalDate through) {
        require(!through.isBefore(LocalDate.of(2026,11,16)) && through.isBefore(DEMO), "History must end Nov16 or Nov17");
        var payments = new ArrayList<Payment>();
        var movements = new ArrayList<Movement>();
        String[] brands={"CU","YAMU_TEST_RESTAURANT","OLIVE_YOUNG","KYOBO","BURGER_KING","EMART","BAEMIN"};
        for(int m=7;m<=11;m++) {
            int days=m==11?through.getDayOfMonth():YearMonth.of(2026,m).lengthOfMonth();
            long target=switch(m){case 7,10->400000;case 8,9->490000;default->15000L*days;};
            long sum=0;
            for(int d=1;d<=days;d++) {
                LocalDate day=LocalDate.of(2026,m,d);
                long amount=(target/days/10)*10+(d-16)*10;
                String brand=brands[(d+m)%brands.length];long benefit=0;
                if(m>=8 && m<=10 && Set.of(5,12,19).contains(d)){brand="STARBUCKS";amount=20000;benefit=4000;}
                if(m==11 && (d==4||d==11)){brand="STARBUCKS";amount=d==4?10000:15000;benefit=amount/5;}
                // Spread the fixed monthly difference over ordinary days, not a single large outlier.
                payments.add(new Payment(day,"NORI",brand,amount,benefit,m==7?"SEED":"YAMU"));sum+=amount;
            }
            long difference=target-sum;
            List<Integer> ordinary=new ArrayList<>();
            for(int i=payments.size()-days;i<payments.size();i++)if(payments.get(i).benefit()==0)ordinary.add(i);
            long each=difference/ordinary.size();long remainder=difference%ordinary.size();
            for(int j=0;j<ordinary.size();j++) {
                int i=ordinary.get(j);var p=payments.get(i);long a=p.amount()+each+(j==0?remainder:0);
                require(a>0 && a<100000,"Invalid daily purchase");
                payments.set(i,new Payment(p.day(),p.card(),p.brand(),a,p.benefit(),p.source()));
            }
        }
        // Gross 100806 - billing discount (400+406) = November withdrawal 100000.
        payments.add(new Payment(LocalDate.of(2026,10,28),"ZERO","COUPANG",50000,400,"YAMU"));
        payments.add(new Payment(LocalDate.of(2026,10,30),"ZERO","KYOBO",50806,406,"YAMU"));
        payments.add(new Payment(LocalDate.of(2026,11,12),"ZERO","OLIVE_YOUNG",12500,100,"YAMU"));
        payments.add(new Payment(LocalDate.of(2026,11,15),"ZERO","COUPANG",25000,200,"YAMU"));
        payments.sort(Comparator.comparing(Payment::day).thenComparing(Payment::card));
        for(var p:payments) if(p.card().equals("NORI")) {
            movements.add(new Movement(p.day(),LocalTime.NOON,"체크 결제: "+p.brand(),-p.amount()));
            // Completed historical cashback is not invented as a current forecast inflow.
            if(p.benefit()>0 && !p.day().plusDays(3).isAfter(through))
                movements.add(new Movement(p.day().plusDays(3),LocalTime.of(10,0),"노리 환급: "+p.day(),p.benefit()));
        }
        for(int m=7;m<=11;m++) {
            add(movements,through,m,20,"월세",-400000);
            add(movements,through,m,22,"통신비",-70000);
            add(movements,through,m,25,"급여",2500000);
            add(movements,through,m,26,"저축 계좌로 이체",-2000000);
        }
        // Previously settled planned large purchases, separate from daily living cost.
        for(int m=8;m<=11;m++) add(movements,through,m,16,"LOCA 대형 구매 청구 납부",m==11?-2074800:-1976000);
        movements.sort(Comparator.comparing(Movement::day).thenComparing(Movement::time).thenComparing(Movement::label));
        long opening=800000-movements.stream().mapToLong(Movement::signedAmount).sum();
        require(opening>=0,"Negative opening balance");
        long balance=opening;var rows=new ArrayList<Row>();
        for(var move:movements){balance=Math.addExact(balance,move.signedAmount());require(balance>=0,"Negative intermediate balance");rows.add(new Row(move,balance));}
        require(balance==800000,"Closing balance mismatch");
        return new Plan(List.copyOf(payments),List.copyOf(rows),opening,balance);
    }
    private static void add(List<Movement> moves,LocalDate through,int m,int d,String label,long value){
        var date=LocalDate.of(2026,m,d);if(!date.isAfter(through))moves.add(new Movement(date,LocalTime.of(9,0),label,value));
    }
    static void require(boolean valid,String safeMessage){if(!valid)throw new SeedFailure(safeMessage);}
    static final class SeedFailure extends RuntimeException { SeedFailure(String message){super(message);} }
}

