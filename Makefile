TARGET?=	master_thesis
BIBFILE?=	thesis.bib
NOMFILE?=	thesis.nlo

TARGETFILES?=	preamble.tex \
		settings.tex \
		title.tex \
		abstract.tex \
		introduction.tex \
		targeting.tex \
		intellectualSystem.tex \
		cloud.tex \
		architecture.tex \
		developing.tex \
		chapter01.tex \
		chapter02.tex \
		chapter03.tex \
		conclusion.tex \
		appendix01.tex

default: pdf-fast

include include/latex.mk
