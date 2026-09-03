#!/usr/bin/perl
#
# import-cvs-history.pl — одноразовий перенос історії графіків чергувань
# з CVS-репозиторію застарілого проєкту в git-історію nextgen.
#
# Для кожної ревізії кожного файлу графіка (YYYYMM та vacation) з
# ../safe/duty/cvs/cvsroot/duty/ дістає вміст через `co -p`, перекодовує
# KOI8-U -> UTF-8, записує в data/duty/ і робить окремий git-коміт
# з ОРИГІНАЛЬНИМИ датою та автором ревізії — щоб git log відображав
# справжню хронологію редагувань 2008-2026 років, а не єдиний "імпорт".
#
# Технічні "1.1 / Initial revision" ревізії (побічний продукт `cvs import`,
# вміст завжди байт-в-байт ідентичний наступній ревізії на тій самій
# секунді) відкидаються — інформаційної цінності не несуть.
#
# Для 166 місяців (2008-11 .. 2022-08 + файл vacation), імпортованих у CVS
# одним пакетом 2022-07-26 (справжня історія редагувань до цієї дати в CVS
# не збереглася), коміт-повідомлення переписується на явне пояснення цього
# факту — щоб не видавати дату міграції за дату реального редагування.
# Решта повідомлень ("Spasiba. Abnavil." — ручні правки, "script output" —
# автогенерація tds.pl) лишаються як є, з префіксом імені файлу.
#
# Запуск:
#   perl import-cvs-history.pl --dry-run   # показати план, нічого не чіпати
#   perl import-cvs-history.pl --commit    # виконати імпорт (git add+commit)
#
# Разова дія: після успішного запуску повторно не виконувати.

use strict;
use warnings;
use utf8;
use Encode qw(decode encode);
use File::Basename;
use Cwd qw(abs_path);

binmode(STDOUT, ':encoding(UTF-8)');
binmode(STDERR, ':encoding(UTF-8)');

my $MODE = shift(@ARGV) // '--dry-run';
die "usage: $0 --dry-run|--commit\n" unless $MODE eq '--dry-run' || $MODE eq '--commit';

my $SCRIPT_DIR    = dirname(abs_path($0));
my $NEXTGEN_DIR   = abs_path("$SCRIPT_DIR/..");
my $REPO_ROOT     = abs_path("$NEXTGEN_DIR/..");
my $CVSROOT_DUTY  = "$REPO_ROOT/safe/duty/cvs/cvsroot/duty";
my $OUT_DIR       = "$NEXTGEN_DIR/data/duty";

my %AUTHOR_MAP = (
    olden => ['Олександр Русских', 'olden@ukr-com.net'],
    root  => ['Duty CVS Migration', 'root@duty.ukrhub.net'],
    noc   => ['Duty Automation',    'noc@duty.ukrhub.net'],
);

opendir(my $dh, $CVSROOT_DUTY) or die "can't open $CVSROOT_DUTY: $!";
my @files = grep { /^(\d{6}|vacation),v$/ } readdir($dh);
closedir $dh;
die "не знайдено жодного ,v файлу в $CVSROOT_DUTY\n" unless @files;

my @entries; # { base, path, rev, date, author, message }

for my $f (sort @files) {
    my $base = $f;
    $base =~ s/,v$//;
    my $path = "$CVSROOT_DUTY/$f";

    open(my $rh, '-|', 'rlog', $path) or die "rlog $path: $!";
    local $/ = undef;
    my $out = <$rh>;
    close $rh;

    my @blocks = split /^-{28}\n/m, $out;
    shift @blocks; # заголовок файлу — не ревізія

    for my $b (@blocks) {
        $b =~ s/^=+\n?//mg;
        next unless $b =~ /^revision\s+(\S+)\n/m;
        my $rev = $1;
        my ($date)   = $b =~ /^date:\s+([0-9\/]+ [0-9:]+);/m;
        my ($author) = $b =~ /author:\s+([^;]+);/;
        my ($msg)    = $b =~ /^date:[^\n]*\n(.*)/sm;
        $msg =~ s/^branches:[^\n]*\n//;   # службовий рядок про гілку, не частина повідомлення
        $msg =~ s/^\s+|\s+$//g;

        next if $msg eq 'Initial revision'; # технічний артефакт cvs import

        die "невідомий автор '$author' у $base\@$rev\n" unless $AUTHOR_MAP{$author};

        push @entries, {
            base => $base, path => $path, rev => $rev,
            date => $date, author => $author, message => $msg,
        };
    }
}

my $sortable = sub { my $d = $_[0]->{date}; $d =~ tr#/#-#; return $d; };
@entries = sort { $sortable->($a) cmp $sortable->($b) or $a->{base} cmp $b->{base} } @entries;

printf STDERR "Знайдено %d ревізій для імпорту (з %d файлів).\n", scalar(@entries), scalar(@files);

if ($MODE eq '--dry-run') {
    for my $e (@entries) {
        printf "%-8s %-8s %-20s %-6s %s\n", $e->{base}, $e->{rev}, $e->{date}, $e->{author}, $e->{message};
    }
    print STDERR "\n(--dry-run: нічого не записано і не закомічено)\n";
    exit 0;
}

mkdir $OUT_DIR unless -d $OUT_DIR;

my $count = 0;
for my $e (@entries) {
    $count++;

    open(my $ch, '-|', 'co', '-q', '-p', "-r$e->{rev}", $e->{path})
        or die "co -p -r$e->{rev} $e->{path}: $!";
    binmode $ch;
    local $/ = undef;
    my $raw = <$ch>;
    close $ch;
    die "co повернув порожній вміст для $e->{base}\@$e->{rev}\n" unless defined $raw;

    my $text = decode('koi8-u', $raw);

    my $outfile = "$OUT_DIR/$e->{base}";
    open(my $wh, '>:encoding(UTF-8)', $outfile) or die "write $outfile: $!";
    print $wh $text;
    close $wh;

    my $message = $e->{message};
    if ($message eq 'test') {
        $message = ($e->{base} eq 'vacation')
            ? 'Архівний імпорт з CVS: облік відпусток (стан на момент міграції репозиторію, 2022-07-26; попередня історія редагувань не збереглася)'
            : "Архівний імпорт з CVS: графік $e->{base} (стан на момент міграції репозиторію, 2022-07-26; попередня історія редагувань не збереглася)";
    } else {
        $message = "$e->{base}: $message";
    }

    my ($name, $email) = @{ $AUTHOR_MAP{ $e->{author} } };
    (my $gitdate = $e->{date}) =~ s#/#-#g;
    $gitdate .= ' +0000';

    local %ENV = %ENV;
    $ENV{GIT_AUTHOR_NAME}     = $name;
    $ENV{GIT_AUTHOR_EMAIL}    = $email;
    $ENV{GIT_AUTHOR_DATE}     = $gitdate;
    $ENV{GIT_COMMITTER_NAME}  = $name;
    $ENV{GIT_COMMITTER_EMAIL} = $email;
    $ENV{GIT_COMMITTER_DATE}  = $gitdate;

    my $relpath = "data/duty/$e->{base}";
    system('git', '-C', $REPO_ROOT, 'add', '--', $relpath) == 0
        or die "git add $relpath: $?";
    system('git', '-C', $REPO_ROOT, 'commit', '--quiet', '-m', $message, '--', $relpath) == 0
        or die "git commit $relpath ($e->{base}\@$e->{rev}): $?";

    printf STDERR "[%d/%d] %s\n", $count, scalar(@entries), $message if $count % 25 == 0;
}

printf STDERR "Готово: %d комітів.\n", $count;
