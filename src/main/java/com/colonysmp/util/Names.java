package com.colonysmp.util;

import java.util.concurrent.ThreadLocalRandom;

/** Names for citizens and travelers. */
public final class Names {

    private static final String[] FIRST = {
            "Ivan", "Olga", "Dmitri", "Natasha", "Sergei", "Yuri", "Anya", "Boris", "Katya", "Mikhail",
            "Vera", "Pavel", "Lena", "Alexei", "Irina", "Nikolai", "Sasha", "Tanya", "Viktor", "Galina",
            "Oleg", "Zoya", "Fyodor", "Nadia", "Grigori", "Ludmila", "Leonid", "Sveta", "Arkady", "Raisa",
            "Anton", "Masha", "Kostya", "Yelena", "Roman", "Dasha", "Ilya", "Polina", "Stepan", "Inna",
            "Bogdan", "Marta", "Tomas", "Hana", "Jakub", "Zofia", "Milan", "Jana", "Emil", "Klara"
    };
    private static final String[] LAST = {
            "Petrenko", "Kovalenko", "Shevchenko", "Bondar", "Novak", "Kowalski", "Horvat", "Popescu", "Melnyk",
            "Tkachenko", "Kravets", "Volkov", "Sokol", "Zima", "Orlov", "Lebed", "Morozov", "Belov", "Kuznets",
            "Smirnov", "Pavlenko", "Moroz", "Hrabe", "Dvorak", "Svoboda", "Kral", "Zelenko", "Vasko", "Rudenko",
            "Gromov", "Tarasov", "Kozak", "Lisitsa", "Medved", "Sorokin", "Yablonsky", "Voronin", "Chernov",
            "Zaitsev", "Kolesnik", "Fedorov", "Markov", "Ryabov", "Kirov", "Stepanek", "Nowicki", "Wojcik"
    };

    private Names() {}

    public static String random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
    }
}
