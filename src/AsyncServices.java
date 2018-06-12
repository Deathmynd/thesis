package server.service;

import java.lang.reflect.Type;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import server.dao.*;
import server.entity.*;

enum RepeatabilityType {
    once, daysOfWeek, lastDays
}

@Service
public class AsyncServices {

    //Время в секундах между проверками
    int CHECK_REPETETION_TIME = 10;

    @Autowired
    RuleDao ruleDao;

    @Autowired
    ClientDao clientDao;

    @Autowired
    CoolDownDao coolDownDao;

    @Autowired
    VisitDao visitDao;

    @Autowired
    RouterDao routerDao;

    Logger log = LoggerFactory.getLogger(this.getClass().getName());

    @Async
    public void process() throws InterruptedException {
        try {
            while (true) {
                List<Rule> rules = ruleDao.findAll();

                if (rules.isEmpty()) return;

                for (Rule r : rules) {
                    checkRule(r);
                }

                TimeUnit.SECONDS.sleep(CHECK_REPETETION_TIME);
            }
        } catch (InterruptedException e) {
            System.out.println("Exception catched. Stack trace:");
            e.printStackTrace();
        }
    }

    private void checkRule(Rule rule) {
        JsonParser parser = new JsonParser();
        JsonObject mainObject = parser.parse(rule.getRule()).getAsJsonObject();

        Long routerId = -1L;
        int lowerBound;
        int upperBound;
        Gender gender;
        RepeatabilityType repeatabilityType;

        //Извлечение простых параметров
        lowerBound = mainObject.getAsJsonObject("age").getAsJsonPrimitive("ge").getAsInt();
        upperBound = mainObject.getAsJsonObject("age").getAsJsonPrimitive("le").getAsInt();
        gender = Gender.valueOf(mainObject.getAsJsonPrimitive("gender").getAsString());

        JsonObject repeatability = mainObject.getAsJsonObject("repeatability");
        repeatabilityType = RepeatabilityType.valueOf(repeatability
                .getAsJsonPrimitive("type")
                .getAsString());

        //Извлечение роутеров
        ArrayList<Router> routersList = new ArrayList<>();
        if (mainObject.has("router")) {
            JsonElement jElement = mainObject.get("router");
            Type listType = new TypeToken<List<Long>>() {}.getType();
            List<Long> routersIdList = new Gson().fromJson(jElement, listType);
            for (Long l : routersIdList) {
                Optional<Router> r = routerDao.findById(l);
                if (r.isPresent()) {
                    routersList.add(r.get());
                }
            }
        }
        if (routersList.isEmpty()) {
            return;
        }

        List<Client> clients = clientDao.findAllByAgeBetweenAndGenderEquals(lowerBound, upperBound, gender);

        if (clients.isEmpty()) return;

        for (Client cl : clients) {
            List<Visit> visits = getVisitsByClientAndRoutars(cl, routersList);
            if (visits.isEmpty()){
                return;
            }

            switch (repeatabilityType) {
                case once:

                    if (checkOnce(cl, visits)) {
                        doAction(cl, rule.getAction());
                    }
                    break;
                case daysOfWeek:
                    if (checkDaysOfWeek(cl,
                            repeatability.getAsJsonPrimitive("daysOfWeekMask").getAsString(),
                            visits)) {
                        doAction(cl, rule.getAction());
                    }
                    break;
                case lastDays:
                    if (checkLastDays(cl,
                            repeatability.getAsJsonPrimitive("repetitions").getAsInt(),
                            LocalDate.now(),
                            visits)) {
                        doAction(cl, rule.getAction());
                    }
                    break;
                default:
                    System.out.println("Exception: Unexpected RepeatabilityType:" + repeatabilityType.toString());
                    break;
            }
        }
    }

    //Проверяет подходит ли клиент под роутер
    private boolean checkOnce(Client client, List<Visit> visits){
        if (visits.isEmpty())
            return false;

        for (Visit v : visits){
            LocalDateTime nowTime =LocalDateTime.now(Clock.systemUTC()).minusSeconds(CHECK_REPETETION_TIME);
            if (v.getTimeIn().isAfter(nowTime)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkLastDays(Client client, int number, LocalDate date, List<Visit> visits) {

        List<LocalDate> dates = new ArrayList<LocalDate>();
        for (int i = 1; i <= number; i++) {
            dates.add(date.minusDays(i));
        }

        return checkDaysInVisits(dates, visits);
    }

    private boolean checkDaysOfWeek(Client client, String daysOfWeekMask, List<Visit> visits) {
        List<LocalDate> searchDates = weekMaskTodates(daysOfWeekMask, LocalDate.now());
        return checkDaysInVisits(searchDates, visits);
    }

    private boolean checkDaysInVisits(List<LocalDate> days, List<Visit> visits) {
        boolean accept = true;
        for (LocalDate date : days) {
            boolean isPresent = false;
            for (Visit visit : visits) {
                //Время входа
                LocalDate dayIn = visit.getTimeIn().toLocalDate();
                if (dayIn.compareTo(date) == 0) {
                    isPresent = true;
                    break;
                }
                //Время выхода
                LocalDate dayOut = visit.getTimeIn().toLocalDate();
                if (dayOut.compareTo(date) == 0) {
                    isPresent = true;
                    break;
                }
            }
            if (!isPresent) {
                accept = false;
                break;
            }
        }

        if (accept)
            return true;
        return false;
    }

    private List<LocalDate> weekMaskTodates(String mask, LocalDate date) {

        List<LocalDate> list = new ArrayList<LocalDate>();

        if (mask.length() < 7 || mask.length() > 7) {
            System.out.println("Error when parse dayOfWeekMask: " + mask);
        }

        //Начиная со вчерашнего дня
        for (int i = 1; i <= mask.length(); i++) {
            LocalDate newDay = date.minusDays(i);
            int maskId = newDay.getDayOfWeek().getValue();
            if (mask.charAt(maskId - 1) == '1') {
                list.add(newDay);
            }
        }

        return list;
    }

    private boolean checkActionCD(Client client, Action action) {
        Optional<CoolDown> optCd = coolDownDao.findByClientIdAndActionId(client.getId(), action.getId());

        //Если записи еще не существует, то правило ранее не применялось
        if (!optCd.isPresent())
            return true;

        CoolDown cd = optCd.get();
        long days = ChronoUnit.DAYS.between(cd.getLastUse(), LocalDateTime.now());
        if (days < action.getCoolDown())
            return false;
        else
            return true;
    }

    private void doAction(Client client, Action action) {
        if (!checkActionCD(client, action)) {
            return;
        }

        //TODO do action
        System.out.println("Send sms:\"" + action.getDescription() + "\" "
        + "to client with phone " + client.getPhone()
        + ". Action id: " + action.getId());

        CoolDown cd = new CoolDown(client.getId(), action.getId(), LocalDateTime.now());

        Optional<CoolDown> optCd = coolDownDao.findByClientIdAndActionId(client.getId(), action.getId());
        if (optCd.isPresent()) {
            cd.setId(optCd.get().getId());
        }
        coolDownDao.save(cd);
    }

    private List<Visit> getVisitsByClientAndRoutars(Client client, List<Router> routers) {
        ArrayList<Visit> visits = new ArrayList<>();
        for (Visit v: visitDao.findAllByClient(client)){
            if (routers.contains(v.getRouter())) {
                visits.add(v);
            }
        }
        return visits;
    }
}